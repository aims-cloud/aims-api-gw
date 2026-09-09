package com.innogrid.aims.monitoring.log;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.function.IntSupplier;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 포털 로그 보존 배치 (docs/08 §9) — {@code started_at} 이 보존 기간을 넘은 행을 매일 지운다. 기본 1년, SYSTEM 은 90일.
 *
 * <ul>
 *   <li>배치 단위({@code batch-size})로 나눠 각각 별도 트랜잭션으로 지운다 — 한 번에 수십만 행을 지우면 락·WAL 이 커지고
 *       공용 스케줄러 스레드(풀 크기 1)를 오래 잡는다.</li>
 *   <li>한 실행에서 {@code max-batches-per-run} 을 넘으면 멈추고 다음 실행에 넘긴다(첫 도입·장기 중단 뒤 적체 대비).</li>
 *   <li>지운 행이 있으면 SYSTEM 행 1건으로 요약을 남기고, 실패는 {@link IntegrationHealth} 로 정상↔실패 전이에서만 남긴다.</li>
 * </ul>
 * 실행 시각은 새벽 03:30 — 자정의 미터링 정리와 같은 스레드를 쓰므로 겹치지 않게 뺐다.
 */
@Component
public class PortalLogRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(PortalLogRetentionJob.class);

    static final String SCREEN = "포털 로그 보존";
    static final String HEALTH_KEY = "cleanup";

    /** 한 번 실행의 결과 — 타입별 삭제 건수와 배치 한도로 멈췄는지 */
    public record Result(int deletedDefault, int deletedSystem, boolean truncated) {
        public int total() { return deletedDefault + deletedSystem; }
    }

    /** 배치 반복 결과 — 삭제 합계, 쓴 배치 수, 한도에 걸려 남은 행이 있을 수 있는지 */
    private record Batches(int deleted, int used, boolean exhausted) {}

    private final PortalLogRepository repository;
    private final TransactionTemplate tx;
    private final PortalLogProperties properties;
    private final PortalLogService portalLogService;
    private final IntegrationHealth health;
    private final Clock clock;

    @Autowired
    public PortalLogRetentionJob(PortalLogRepository repository, TransactionTemplate tx,
                                 PortalLogProperties properties, PortalLogService portalLogService) {
        this(repository, tx, properties, portalLogService, Clock.systemDefaultZone());
    }

    public PortalLogRetentionJob(PortalLogRepository repository, TransactionTemplate tx,
                                 PortalLogProperties properties, PortalLogService portalLogService, Clock clock) {
        this.repository = repository;
        this.tx = tx;
        this.properties = properties;
        this.portalLogService = portalLogService;
        this.health = new IntegrationHealth(portalLogService, SCREEN);
        this.clock = clock;
    }

    @Scheduled(cron = "${aims.portal-log.retention.cron:0 30 3 * * *}")
    public void scheduled() {
        if (!properties.getRetention().isEnabled()) {
            return;
        }
        run();
    }

    /** 보존 정리 1회 실행. 예외는 여기서 끝난다 — 스케줄러 스레드로 새지 않는다 */
    public Result run() {
        PortalLogProperties.Retention r = properties.getRetention();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime defaultCutoff = now.minusDays(r.getDefaultDays());
        OffsetDateTime systemCutoff = now.minusDays(r.getSystemDays());
        String system = PortalLogType.SYSTEM.name();
        try {
            Batches sys = deleteInBatches(r.getMaxBatchesPerRun(),
                    () -> repository.deleteSystemOlderThan(system, systemCutoff, r.getBatchSize()));
            Batches others = deleteInBatches(r.getMaxBatchesPerRun() - sys.used(),
                    () -> repository.deleteOthersOlderThan(system, defaultCutoff, r.getBatchSize()));
            Result result = new Result(others.deleted(), sys.deleted(), sys.exhausted() || others.exhausted());

            health.recovered(HEALTH_KEY, "보존 정리");
            if (result.total() > 0) {
                String tail = result.truncated() ? " — 배치 한도 도달, 다음 실행에 계속" : "";
                log.info("포털 로그 보존 정리: 총 {}건 삭제 (일반 {}건 < {}, SYSTEM {}건 < {}){}", result.total(),
                        result.deletedDefault(), defaultCutoff, result.deletedSystem(), systemCutoff, tail);
                portalLogService.system(SCREEN, "정리",
                        String.format("포털 로그 보존 정리: %,d건 삭제 (일반 %,d건 / %d일, SYSTEM %,d건 / %d일)%s",
                                result.total(), result.deletedDefault(), r.getDefaultDays(),
                                result.deletedSystem(), r.getSystemDays(), tail),
                        PortalLogResult.SUCCESS, null);
            } else {
                log.debug("포털 로그 보존 정리: 삭제 대상 없음 (일반 < {}, SYSTEM < {})", defaultCutoff, systemCutoff);
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("포털 로그 보존 정리 실패: {}", e.getMessage(), e);
            health.failed(HEALTH_KEY, "보존 정리", e);
            return new Result(0, 0, false);
        }
    }

    /** 배치 반복 — 각 배치는 별도 트랜잭션. 삭제 건수가 배치 크기보다 작으면 더 없다는 뜻이라 멈춘다 */
    private Batches deleteInBatches(int maxBatches, IntSupplier deleteBatch) {
        int batchSize = properties.getRetention().getBatchSize();
        int total = 0;
        int used = 0;
        while (used < maxBatches) {
            Integer deleted = tx.execute(status -> deleteBatch.getAsInt());
            used++;
            int n = deleted == null ? 0 : deleted;
            total += n;
            if (n < batchSize) {
                return new Batches(total, used, false);
            }
        }
        return new Batches(total, used, true);          // 한도까지 전부 가득 찬 배치였다 → 남은 행이 있을 수 있다
    }
}

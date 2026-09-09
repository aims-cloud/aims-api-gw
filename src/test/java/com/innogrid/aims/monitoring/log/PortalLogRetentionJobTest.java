package com.innogrid.aims.monitoring.log;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 포털 로그 보존 배치 (docs/08 §9) — 일반 1년·SYSTEM 90일 기준으로 started_at 이 지난 행만 지우고, 배치 크기로 나눠 돌며,
 * 배치 한도에 걸리면 truncated 로 알리고, 지운 게 있을 때만 SYSTEM 요약 행을 남긴다. 배치마다 커밋하므로 @Transactional 없이 돈다.
 *
 * 대역 (now = 2026-09-09 03:30 KST): SYSTEM 100일 전(삭제)·80일 전(유지), AUDIT 400일 전(삭제)·200일 전(유지), AUTH 366일 전(삭제)
 */
@SpringBootTest
class PortalLogRetentionJobTest {

    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 9, 3, 30, 0, 0, ZoneOffset.ofHours(9));

    @Autowired PortalLogRepository repository;
    @Autowired TransactionTemplate tx;

    PortalLogService portalLogService = mock(PortalLogService.class);
    PortalLogProperties properties = new PortalLogProperties();
    PortalLogRetentionJob job;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        save(PortalLogType.SYSTEM, NOW.minusDays(100), "system-old");
        save(PortalLogType.SYSTEM, NOW.minusDays(80), "system-recent");
        save(PortalLogType.AUDIT, NOW.minusDays(400), "audit-old");
        save(PortalLogType.AUDIT, NOW.minusDays(200), "audit-recent");
        save(PortalLogType.AUTH, NOW.minusDays(366), "auth-old");
        properties.getRetention().setBatchSize(2);
        job = new PortalLogRetentionJob(repository, tx, properties, portalLogService, Clock.fixed(NOW.toInstant(), ZoneOffset.ofHours(9)));
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    private void save(PortalLogType type, OffsetDateTime startedAt, String content) {
        repository.save(PortalLogEntry.builder(type).startedAt(startedAt).completedAt(startedAt)
                .screen("t").action("t").content(content).build());
    }

    private List<String> remaining() {
        return repository.findAll().stream().map(PortalLogEntry::getContent).sorted().toList();
    }

    @Test
    void 일반_1년_SYSTEM_90일을_넘은_행만_배치로_지우고_요약_SYSTEM_행을_남긴다() {
        PortalLogRetentionJob.Result result = job.run();

        assertThat(result.deletedDefault()).isEqualTo(2);            // audit-old, auth-old (배치 2 → 2건 + 0건 확인 배치)
        assertThat(result.deletedSystem()).isEqualTo(1);             // system-old
        assertThat(result.truncated()).isFalse();
        assertThat(remaining()).containsExactly("audit-recent", "system-recent");
        verify(portalLogService).system(eq("포털 로그 보존"), eq("정리"), contains("3건 삭제"), eq(PortalLogResult.SUCCESS), any());
    }

    @Test
    void 지울_행이_없으면_요약_행을_남기지_않는다() {
        job.run();
        org.mockito.Mockito.reset(portalLogService);

        PortalLogRetentionJob.Result second = job.run();

        assertThat(second.total()).isZero();
        verify(portalLogService, never()).system(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void 배치_한도에_걸리면_멈추고_truncated_로_알린다() {
        properties.getRetention().setBatchSize(1);
        properties.getRetention().setMaxBatchesPerRun(1);

        PortalLogRetentionJob.Result result = job.run();

        assertThat(result.deletedSystem()).isEqualTo(1);             // SYSTEM 1배치만 쓰고 한도 소진
        assertThat(result.deletedDefault()).isZero();
        assertThat(result.truncated()).isTrue();
        assertThat(remaining()).containsExactly("audit-old", "audit-recent", "auth-old", "system-recent");
        verify(portalLogService).system(eq("포털 로그 보존"), eq("정리"), contains("다음 실행에 계속"), eq(PortalLogResult.SUCCESS), any());
    }

    @Test
    void 보존_일수를_바꾸면_기준이_함께_움직인다() {
        properties.getRetention().setDefaultDays(150);
        properties.getRetention().setSystemDays(30);

        job.run();

        assertThat(remaining()).isEmpty();
    }
}

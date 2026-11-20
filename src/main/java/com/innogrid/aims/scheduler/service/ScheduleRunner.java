package com.innogrid.aims.scheduler.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.log.IntegrationHealth;
import com.innogrid.aims.monitoring.log.PortalLogService;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.repository.PlacementInputRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunInputRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

/**
 * 자동 실행 러너 — 실행할 때가 된 스케줄을 찾아 돌린다.
 *
 * <p><b>{@code @Scheduled}를 쓰지 않는다.</b> 이 앱의 작업 스케줄러 풀 크기는 기본값 1
 * 이고, 미터링 집계·알람 평가 등 다른 담당자의 {@code @Scheduled} 작업이 모두 그 스레드 하나를 함께 쓴다.
 * 여기에 배치 작업을 얹으면 노드 메트릭 조회가 끝날 때까지 그 작업들이 밀린다. 그래서
 * 전용 executor 두 개(폴링용·실행용)를 따로 만든다.</p>
 *
 * <p>스레드는 모두 데몬이라 JVM 종료를 막지 않고, {@link #stop()}에서 정리한다.
 * 폴링 본문은 통째로 예외를 삼킨다 — 여기서 예외가 새어 나가면 스케줄러 스레드가
 * 죽어 자동 실행이 조용히 멈춘다.</p>
 *
 * <p>기동 직후 {@code initial-delay-seconds}(기본 30초) 동안은 아무것도 하지 않는다.
 * DB·외부 연동이 준비되기 전에 도는 것을 막고, 테스트 컨텍스트에서도 폴링이 일어나지
 * 않게 한다.</p>
 *
 * <p>자동 실행의 실패 · 복구는 포털 로그에 SYSTEM 행으로 남긴다(화면 「스케줄 자동 실행」). 상태가 바뀔 때만 1행이고,
 * 판정은 포털 로그 모듈의 {@link IntegrationHealth}를 그대로 쓴다. 기동 때 실행 권한을 회수했으면 요약 1행을 남긴다.</p>
 */
@Component
public class ScheduleRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);

    /** 포털 로그 화면 이름 — 팀의 SYSTEM 행 관례대로 하위 시스템 이름을 쓴다(「Thanos 연동」 · 「알람 평가기」와 같은 꼴) */
    static final String PORTAL_LOG_SCREEN = "스케줄 자동 실행";

    private final ScheduleRepository scheduleRepository;
    private final ScheduleRunRepository runRepository;
    private final SchedulingHistoryRepository historyRepository;
    private final ScheduleRunInputRepository inputRepository;
    private final PlacementInputRepository placementInputRepository;
    private final ScheduleExecutionService executionService;
    private final SchedulerProperties properties;
    private final PortalLogService portalLogService;
    /** 자동 실행 실패 · 복구 전이 — 스케줄 PK별로 상태가 바뀔 때만 SYSTEM 행 1개. 빈이 아니라 이 러너 안에서 만든다 */
    private final IntegrationHealth autoRunHealth;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 워커에 넘겼지만 아직 끝나지 않은 스케줄. 큐에서 대기 중인 것까지 포함한다 —
     * DB의 "실행 중" 표시는 실행이 <b>시작된 뒤</b>에야 생겨서 대기 구간을 못 막는다.
     */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    private ScheduledExecutorService poller;
    private ExecutorService worker;

    /** 이력 정리를 마지막으로 수행한 날짜. 하루 한 번만 돌린다. */
    private volatile LocalDate lastCleanupDate;

    public ScheduleRunner(ScheduleRepository scheduleRepository,
                          ScheduleRunRepository runRepository,
                          SchedulingHistoryRepository historyRepository,
                          ScheduleRunInputRepository inputRepository,
                          PlacementInputRepository placementInputRepository,
                          ScheduleExecutionService executionService,
                          SchedulerProperties properties,
                          PortalLogService portalLogService) {
        this.scheduleRepository = scheduleRepository;
        this.runRepository = runRepository;
        this.historyRepository = historyRepository;
        this.inputRepository = inputRepository;
        this.placementInputRepository = placementInputRepository;
        this.executionService = executionService;
        this.properties = properties;
        this.portalLogService = portalLogService;
        this.autoRunHealth = new IntegrationHealth(portalLogService, PORTAL_LOG_SCREEN);
    }

    // ── 수명 주기 ───────────────────────────────────────────────

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;

        recoverStuckRunsQuietly();

        poller = Executors.newSingleThreadScheduledExecutor(daemon("aims-schedule-poller"));
        worker = Executors.newSingleThreadExecutor(daemon("aims-schedule-worker"));

        poller.scheduleWithFixedDelay(this::pollQuietly,
                properties.getInitialDelaySeconds() * 1000L,
                properties.getPollIntervalMs(),
                TimeUnit.MILLISECONDS);

        log.info("스케줄 러너 시작: 확인 주기 {}ms, 최초 지연 {}초, 시간대 {}",
                properties.getPollIntervalMs(), properties.getInitialDelaySeconds(),
                properties.getTimezone());
    }

    /**
     * 기동 시 남아 있는 실행 권한을 회수한다. <b>여기서 예외가 새면 앱이 뜨지 않는다</b> —
     * 스프링 컨텍스트 생성 스레드에서 돌기 때문이다. 그래서 {@code Throwable}까지 삼키고 로그만 남긴다.
     *
     * <p>중복 {@code start()}로 두 번 돌지 않도록 {@code running}을 세운 뒤에 부른다. 폴링 시작 전에
     * 끝나야 회수 대상이 폴러와 겹치지 않는다.</p>
     *
     * <p>회수한 스케줄이 1건 이상이면 포털 로그에 요약 SYSTEM 행 1개를 남긴다. 재기동으로 끊긴 실행은 메모리의 전이 상태가
     * 비어 있어 실패 행이 남지 않기 때문이다. 같은 패키지의 테스트가 직접 부른다.</p>
     */
    void recoverStuckRunsQuietly() {
        int recovered;
        try {
            recovered = executionService.recoverStuckRuns();
        } catch (Throwable t) {
            log.error("기동 회수 실패 — 스케줄러는 계속 기동한다", t);
            return;
        }
        recordRecoverySummary(recovered);
    }

    /** 기동 회수 요약 행. 기동 경로라 어떤 예외도 밖으로 내보내지 않는다 */
    private void recordRecoverySummary(int recovered) {
        if (recovered <= 0) return;
        try {
            portalLogService.system(PORTAL_LOG_SCREEN, "회수",
                    "기동 회수: 실행 중으로 남아 있던 스케줄 " + recovered + "건을 실패로 마감",
                    PortalLogResult.FAILURE, ScheduleExecutionService.RECOVERED_MESSAGE);
        } catch (Throwable t) {
            log.warn("기동 회수 포털 로그 기록 실패: {}", t.getMessage());
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        shutdown(poller, "poller");
        shutdown(worker, "worker");
        log.info("스케줄 러너 종료");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // ── 폴링 ───────────────────────────────────────────────────

    /** 어떤 예외도 밖으로 내보내지 않는다. 한 번 새면 이후 폴링이 영영 멈춘다. */
    private void pollQuietly() {
        try {
            runDueSchedules();
            cleanupOnceADay();
        } catch (Throwable e) {
            log.error("스케줄 폴링 중 오류", e);
        }
    }

    /**
     * 실행할 때가 된 스케줄을 찾아 실행 스레드에 넘긴다. 폴링 스레드는 DB 조회만 한다.
     *
     * <p><b>중복 제출을 두 겹으로 막는다.</b> DB 조회는 "이미 시작한" 실행만 거른다 — 워커가 단일 스레드라
     * 큐에서 <b>차례를 기다리는</b> 스케줄은 아직 실행 중 표시가 없고 예정 시각도 그대로다. 그것만으로는
     * 폴 주기마다 같은 스케줄이 큐에 쌓이고, 앞의 실행이 끝나 표시가 풀리는 순간 쌓인 것들이 차례로
     * <b>정상 실행</b>된다. 그래서 넘긴 스케줄을 기억해 두고 실행이 끝날 때까지 다시 넘기지 않는다.</p>
     *
     * <p>이 기억은 프로세스 안에만 있다 — 재기동하면 비고, 그때는 DB 쪽(실행 중 표시)과 기동 회수가 맡는다.</p>
     *
     * <p>폴링 목록은 <b>누구를 돌릴지</b>만 정한다. 넘긴 엔티티는 큐에서 차례를 기다리는 동안 낡으므로
     * 워커가 시작 직전에 다시 읽어 그 시점 값으로 돈다({@link #executeQuietly}).</p>
     */
    private void runDueSchedules() {
        List<Schedule> due = scheduleRepository
                .findDueForRun(OffsetDateTime.now(), ScheduleRun.RunStatus.RUNNING);
        if (due.isEmpty()) return;

        for (Schedule schedule : due) {
            if (!inFlight.add(schedule.getId())) {
                log.debug("이미 넘긴 스케줄이라 건너뛴다: schedule={}", schedule.getScheduleId());
                continue;
            }
            worker.submit(() -> {
                try {
                    executeQuietly(schedule);
                } finally {
                    inFlight.remove(schedule.getId());
                }
            });
        }
    }

    /**
     * 자동 실행 1건.
     *
     * <p>예정 시각이 지난 스케줄은 <b>한 번만</b> 실행한다. 앱이 사흘 동안 꺼져 있었다고
     * 사흘치를 몰아서 돌리지 않는다 — 배치 결과는 최신 노드 상태에 따라 달라지므로
     * 지난 시각의 실행을 뒤늦게 재현하는 것은 의미가 없다. 다음 예정 시각은 실행이
     * 끝날 때 현재 기준으로 다시 계산된다.</p>
     *
     * <p><b>시작 직전에 스케줄을 다시 읽는다.</b> 넘겨받은 것은 폴링 때의 사본이라 큐에서 기다리는 사이
     * 편집·삭제가 끼면 낡는다. 그 사이 지워졌거나 사용 여부·자동 실행이 꺼졌으면 이 회차는 돌지 않고
     * 아무것도 남기지 않으며(실행 이력·최근 실행 기록·다음 예정 시각 모두 그대로), 리소스 종류·배치 순서·정책·
     * 엔진·상한을 바꿨으면 바뀐 값으로 돈다. 건너뛴 스케줄은 지워졌거나 사용 여부·자동 실행이 꺼진 것이라 폴링
     * 조건에서 빠져 다음 폴링이 다시 집지 않는다. 다시 읽기는 try 안에 둔다 — 밖에 두면 DB 예외가 워커의 Future에 삼켜져 로그 없이
     * 사라진다. 같은 패키지의 테스트가 폴링 사본을 들고 직접 부른다.</p>
     */
    void executeQuietly(Schedule polled) {
        Schedule schedule = null;
        OffsetDateTime finishedBefore = null;
        ScheduleRun run = null;
        try {
            Optional<Schedule> fresh = scheduleRepository.findById(polled.getId());
            if (fresh.isEmpty()) {
                log.info("자동 실행 건너뜀 — 차례를 기다리는 사이 삭제됨: schedule={}", polled.getScheduleId());
                return;
            }
            schedule = fresh.get();
            if (!schedule.isAutoRunnable()) {
                log.info("자동 실행 건너뜀 — 차례를 기다리는 사이 사용 안 함 또는 자동 실행 해제: schedule={}",
                        schedule.getScheduleId());
                return;
            }
            finishedBefore = schedule.getLastRunFinishedAt();
            run = executionService.execute(
                    schedule, ScheduleRun.TriggerType.AUTO, ScheduleActorResolver.SYSTEM);
            if (run == null) {
                log.debug("자동 실행 대상 없음: schedule={}", schedule.getScheduleId());
            } else {
                log.info("자동 실행 완료: schedule={}, status={}, {}",
                        schedule.getScheduleId(), run.getStatus(), run.getMessage());
            }
        } catch (Exception e) {
            // 실행 서비스가 이미 상태를 정리하고 이력에 사유를 남긴다. 여기서는 기록만 한다.
            log.warn("자동 실행 실패: schedule={}, reason={}",
                    polled.getScheduleId(), e.getMessage());
        }
        if (schedule != null) {
            observeAutoRun(schedule, finishedBefore, run);
        }
    }

    /**
     * 자동 실행 1회의 결과를 포털 로그 전이로 남긴다(docs/08 6장의 요청 없는 기록 · {@link IntegrationHealth}).
     *
     * <p>{@code ScheduleExecutionService.complete}는 들고 있는 엔티티에도 결과를 반영한다. 그래서 실행 이력을 돌려줬든,
     * 대상 0건으로 null이든, 실패를 마감한 뒤 예외든 엔티티 하나로 읽는다. 마무리 시각이 그대로면(실행 권한을 못 잡은 409)
     * 이번 회차는 끝난 것이 아니라 보지 않는다.</p>
     *
     * <p>실패는 FAILED이고, 복구는 실행 이력이 있는 COMPLETED만이다. 대상 0건으로 끝난 회차(대상 없음 · 재시도 대기)는
     * 상태를 바꾸지 않는다 — 배치할 수 없는 워크로드가 재시도 간격마다 장애 · 복구 짝을 되풀이하지 않게 하려는 것이다.
     * 관측이 실패해도 자동 실행은 그대로다.</p>
     */
    private void observeAutoRun(Schedule schedule, OffsetDateTime finishedBefore, ScheduleRun run) {
        try {
            OffsetDateTime finishedAfter = schedule.getLastRunFinishedAt();
            if (finishedAfter == null || finishedAfter.equals(finishedBefore)) return;
            String key = "schedule:" + schedule.getId();
            String what = schedule.getScheduleId() + " (id " + schedule.getId() + ")";
            if (schedule.getLastRunStatus() == ScheduleRun.RunStatus.FAILED) {
                autoRunHealth.failed(key, what, new AutoRunFailure(schedule.getLastRunMessage()));
            } else if (schedule.getLastRunStatus() == ScheduleRun.RunStatus.COMPLETED && run != null) {
                autoRunHealth.recovered(key, what);
            }
        } catch (RuntimeException e) {
            log.warn("자동 실행 포털 로그 관측 실패: schedule={}, reason={}", schedule.getScheduleId(), e.getMessage());
        }
    }

    /**
     * 실패 사유 운반용 — {@link IntegrationHealth#failed}가 예외 메시지를 사유로 쓴다. 던지지 않고 스택도 만들지 않는다.
     * 사유가 비어 있으면 「사유 없음」으로 둔다(문자열 "null"이 남지 않게).
     */
    private static final class AutoRunFailure extends RuntimeException {
        AutoRunFailure(String message) {
            super(message == null || message.isBlank() ? "사유 없음" : message, null, false, false);
        }
    }

    // ── 이력 정리 ───────────────────────────────────────────────

    /**
     * 보존 기간이 지난 실행 이력과 그 실행 입력({@code schedule_run_inputs}), 단건 배치 입력({@code placement_inputs})을
     * 하루 한 번 정리한다.
     *
     * <p>배치 결정 이력({@code scheduling_history})은 지우지 않고 연결만 끊는다.
     * 연구 근거 자료이기 때문이다.</p>
     */
    private void cleanupOnceADay() {
        LocalDate today = OffsetDateTime.now().toLocalDate();
        if (today.equals(lastCleanupDate)) return;
        lastCleanupDate = today;

        cleanupBefore(OffsetDateTime.now().minusDays(properties.getRunRetentionDays()));
    }

    /**
     * {@code cutoff} 전의 실행 이력·실행 입력과 단건 배치 입력을 지운다. 단건 배치 입력은 지울 실행이 없어도 지운다 — 실행과
     * 따로 쌓인다. 하루 한 번 가드 밖이라 테스트가 직접 부른다.
     */
    void cleanupBefore(OffsetDateTime cutoff) {
        cleanupRunsStartedBefore(cutoff);
        int removed = placementInputRepository.deleteCapturedBefore(cutoff);
        if (removed > 0) {
            log.info("단건 배치 입력 정리: {}건 삭제 (보존 {}일)", removed, properties.getRunRetentionDays());
        }
    }

    /** {@code cutoff} 전에 시작한 실행 이력과 그 실행 입력을 지우고 결정 이력은 연결만 끊는다. 하루 한 번 가드 밖이라 테스트가 직접 부른다. */
    void cleanupRunsStartedBefore(OffsetDateTime cutoff) {
        List<ScheduleRun> expired = runRepository.findByStartedAtBefore(cutoff);
        if (expired.isEmpty()) return;

        List<Long> runIds = expired.stream().map(ScheduleRun::getId).toList();
        List<SchedulingHistory> linked = historyRepository.findByScheduleRunIdIn(runIds);
        linked.forEach(SchedulingHistory::unlinkRun);
        historyRepository.saveAll(linked);
        inputRepository.deleteByScheduleRunIds(runIds);
        runRepository.deleteAll(expired);

        log.info("실행 이력 정리: {}건 삭제 (보존 {}일), 결정 이력 {}건은 연결만 해제",
                expired.size(), properties.getRunRetentionDays(), linked.size());
    }

    // ── 내부 ────────────────────────────────────────────────────

    private java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private void shutdown(ExecutorService executor, String label) {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("스케줄 러너 {} 스레드가 제때 종료되지 않았습니다", label);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

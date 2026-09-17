package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

/**
 * 스케줄 1회 실행.
 *
 * <pre>
 * 게이트   사용 여부 off        → 409 (자동·즉시 모두)
 *          이미 실행 중          → 409
 *          ※ 워크로드 유무는 게이트가 아니다
 *
 * 대상     리소스 종류 일치 + 상태 PENDING·FAILED + 재시도 여유 있음
 *          우선순위 CRITICAL→LOW, 같으면 등록 순, 1회 최대 500건
 *
 * 수행     노드 스냅샷 1회 → 예약 반영 → 워크로드마다 제약→엔진→배치→예약 누적
 *
 * 판정     대상 0건 · AUTO      → 실행 이력을 만들지 않고 최근 실행 정보만 갱신
 *          대상 0건 · MANUAL    → COMPLETED "대상 없음" 으로 기록
 *          성공 1건 이상         → COMPLETED
 *          대상은 있는데 전부 실패 → FAILED
 *          활성 노드 0건 · 예외   → FAILED
 * </pre>
 *
 * <p>트랜잭션을 이 클래스에 걸지 않는다. 노드 수집이 외부 메트릭 저장소를 호출해 오래
 * 걸리기 때문이다. DB 쓰기는 리포지토리와 {@link PlacementService} 의 짧은 트랜잭션에
 * 맡긴다.</p>
 */
@Service
public class ScheduleExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleExecutionService.class);

    /** 실행 대상으로 삼을 워크로드 상태. 실패한 것도 다시 시도한다(재시도 상한 안에서). */
    private static final List<Workload.WorkloadStatus> TARGET_STATUSES =
            List.of(Workload.WorkloadStatus.PENDING, Workload.WorkloadStatus.FAILED);

    /** 우선순위 높은 것부터, 같으면 먼저 등록된 것부터(현행 기본). */
    static final Comparator<Workload> BY_PRIORITY_THEN_AGE =
            Comparator.comparingInt((Workload w) -> w.getPriority().ordinal()).reversed()
                    .thenComparing(Workload::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * 우선순위 높은 것부터, 같으면 요구 CPU → 요구 메모리가 큰 것부터, 그래도 같으면 먼저 등록된 것부터.
     * 전력 통합에서 큰 워크로드를 먼저 채우면 노드 조각화가 준다(빈 패킹의 first-fit-decreasing). 우선순위가
     * 첫 기준이라 공정성은 그대로다.
     */
    static final Comparator<Workload> BY_PRIORITY_THEN_SIZE =
            Comparator.comparingInt((Workload w) -> w.getPriority().ordinal()).reversed()
                    .thenComparing(Comparator.comparingInt(Workload::getRequiredCpu).reversed())
                    .thenComparing(Comparator.comparingInt(Workload::getRequiredMemoryMb).reversed())
                    .thenComparing(Workload::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));

    /** 스케줄이 고른 배치 순서의 비교자. 지정이 없으면 현행 비교자 객체 그대로다. */
    static Comparator<Workload> orderFor(Schedule schedule) {
        return schedule.resolvePlacementOrder() == Schedule.PlacementOrder.PRIORITY_THEN_SIZE
                ? BY_PRIORITY_THEN_SIZE : BY_PRIORITY_THEN_AGE;
    }

    private final ScheduleRepository scheduleRepository;
    private final ScheduleRunRepository runRepository;
    private final SchedulingHistoryRepository historyRepository;
    private final WorkloadRepository workloadRepository;
    private final SchedulingPolicyRepository policyRepository;
    private final SchedulingService schedulingService;
    private final PlacementService placementService;
    private final ScheduleService scheduleService;
    private final EngineRegistry engineRegistry;
    private final SchedulerProperties properties;
    private final RunClusterSnapshot clusterSnapshot;

    public ScheduleExecutionService(ScheduleRepository scheduleRepository,
                                    ScheduleRunRepository runRepository,
                                    SchedulingHistoryRepository historyRepository,
                                    WorkloadRepository workloadRepository,
                                    SchedulingPolicyRepository policyRepository,
                                    SchedulingService schedulingService,
                                    PlacementService placementService,
                                    ScheduleService scheduleService,
                                    EngineRegistry engineRegistry,
                                    SchedulerProperties properties,
                                    RunClusterSnapshot clusterSnapshot) {
        this.scheduleRepository = scheduleRepository;
        this.runRepository = runRepository;
        this.historyRepository = historyRepository;
        this.workloadRepository = workloadRepository;
        this.policyRepository = policyRepository;
        this.schedulingService = schedulingService;
        this.placementService = placementService;
        this.scheduleService = scheduleService;
        this.engineRegistry = engineRegistry;
        this.properties = properties;
        this.clusterSnapshot = clusterSnapshot;
    }

    /**
     * 사용자가 누른 즉시 실행. 결과가 확정된 실행 이력을 돌려준다.
     */
    public ScheduleRun runNow(Long id, String triggeredBy) {
        Schedule schedule = scheduleService.require(id);
        ScheduleRun run = execute(schedule, ScheduleRun.TriggerType.MANUAL, triggeredBy);
        // 수동 실행은 대상이 없어도 항상 기록하므로 null 이 나올 수 없다.
        return run;
    }

    /**
     * 스케줄을 한 번 실행한다.
     *
     * @return 실행 이력. 자동 실행이 대상 0건으로 끝났으면 {@code null}
     */
    public ScheduleRun execute(Schedule schedule, ScheduleRun.TriggerType triggerType, String triggeredBy) {
        if (!schedule.isEnabled()) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "사용하지 않는 스케줄은 실행할 수 없습니다: " + schedule.getScheduleId());
        }
        OffsetDateTime startedAt = OffsetDateTime.now();
        acquire(schedule, triggerType, startedAt);

        ScheduleRun run = null;
        try {
            SchedulingPolicy policy = resolvePolicy(schedule);
            String engineId = resolveEngine(schedule, policy);
            List<Workload> targets = findTargets(schedule);
            boolean truncated = targets.size() > properties.getMaxWorkloadsPerRun();
            if (truncated) {
                targets = targets.subList(0, properties.getMaxWorkloadsPerRun());
            }

            if (targets.isEmpty()) {
                EmptyTargetOutcome outcome = emptyTargetOutcome(schedule);
                String message = outcome.message();
                if (triggerType == ScheduleRun.TriggerType.AUTO) {
                    // 5분 주기 스케줄 하나가 90일이면 "대상 없음" 행만 25,920개다.
                    // 이력은 남기지 않고 최근 실행 정보만 갱신해 화면에서 확인할 수 있게 한다.
                    complete(schedule, outcome.status(), startedAt, message);
                    return null;
                }
                run = startRun(schedule, triggerType, triggeredBy, engineId, policy);
                run.recordCounts(0, 0, 0, false);
                run.markSnapshotSkipped();   // 노드를 모으기 전에 끝난다 — 잴 판이 없다
                return finishRun(schedule, run, outcome.status(), startedAt, message);
            }

            run = startRun(schedule, triggerType, triggeredBy, engineId, policy);

            PlacementContext context = schedulingService.createContext(policy)
                    .withPowerBudget(schedule.getMaxPowerBudgetWatts());
            if (context.isEmpty()) {
                run.recordCounts(targets.size(), 0, 0, truncated);
                run.markSnapshotSkipped();
                return finishRun(schedule, run, ScheduleRun.RunStatus.FAILED, startedAt,
                        SchedulingService.noActiveNodesReason(context));
            }

            recordSnapshotBefore(run, context);

            int success = 0;
            int attempted = 0;
            String firstFailure = null;
            try {
                for (Workload workload : targets) {
                    SchedulingResult result = schedulingService.dispatch(workload,
                            placementService.place(workload, context, engineId, run.getId()), run.getId());
                    attempted++;
                    if (result.success()) {
                        success++;
                    } else if (firstFailure == null) {
                        firstFailure = result.reason();
                    }
                }
            } catch (RuntimeException e) {
                // 루프 중간에 끊겨도 그때까지의 결과는 사실이다. 집계를 0 으로 두면 실제로 배치된
                // 워크로드가 이력에서 사라져, 다음 실행이 무엇을 이어서 해야 하는지 알 수 없다.
                // 그때까지 쌓인 예약도 사실이므로 실행 후 상태를 남긴다.
                run.recordCounts(targets.size(), success, attempted - success, truncated);
                recordSnapshotAfter(run, context);
                runRepository.save(run);
                throw e;
            }

            int failed = targets.size() - success;
            run.recordCounts(targets.size(), success, failed, truncated);
            recordSnapshotAfter(run, context);

            if (success == 0) {
                return finishRun(schedule, run, ScheduleRun.RunStatus.FAILED, startedAt,
                        String.format("대상 %d건 전부 배치 실패 — %s", targets.size(), firstFailure));
            }
            String message = String.format("대상 %d건 / 성공 %d건 / 실패 %d건%s",
                    targets.size(), success, failed, truncated ? " (상한 초과분은 다음 실행으로)" : "");
            return finishRun(schedule, run, ScheduleRun.RunStatus.COMPLETED, startedAt, message);

        } catch (BusinessException e) {
            failQuietly(schedule, run, startedAt, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("스케줄 실행 실패: schedule={}", schedule.getScheduleId(), e);
            failQuietly(schedule, run, startedAt, "실행 중 오류: " + e.getMessage());
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "스케줄 실행에 실패했습니다: " + e.getMessage());
        } catch (Error e) {
            // 배포는 실행 중인 JVM 의 jar 를 덮어쓴다. 아직 로드되지 않은 클래스를 그때 읽으면
            // NoClassDefFoundError 가 나는데, RuntimeException 만 잡으면 실행 권한이 잡힌 채 남는다.
            log.error("스케줄 실행 중단(Error): schedule={}", schedule.getScheduleId(), e);
            failQuietly(schedule, run, startedAt, "실행 중단: " + e);
            throw e;
        }
    }

    /**
     * 실행 <b>전</b> 클러스터 상태를 실행 이력에 적는다.
     *
     * <p>계산이 터져도 <b>실행은 계속한다</b>. 스냅샷은 곁들이는 기록이지 배치의 일부가 아니어서, 여기서
     * 예외를 밖으로 내보내면 잘 돌던 배치가 통째로 실패로 뒤바뀐다. 대신 상태를 실패로 남겨 빈 칸이
     * "안 쟀다"인지 "재다 터졌다"인지 구분되게 한다.</p>
     */
    private void recordSnapshotBefore(ScheduleRun run, PlacementContext context) {
        try {
            double activeCpuPercent = properties.getPower().getActiveCpuPercent();
            RunClusterSnapshot.Fixed fixed = clusterSnapshot.fixedOf(context, activeCpuPercent);
            RunClusterSnapshot.Moving moving = clusterSnapshot.movingOf(context, activeCpuPercent);
            run.recordSnapshotBefore(fixed.nodeCount(), fixed.excludedCount(), fixed.measuredNodes(),
                    fixed.measuredPowerWatts(),
                    moving.activeNodes(), moving.reclaimableWatts(), moving.avgTemperatureCelsius());
        } catch (RuntimeException e) {
            log.warn("실행 전 클러스터 스냅샷 기록 실패(배치는 계속한다): run={}", run.getId(), e);
            run.markSnapshotFailed();
        }
    }

    /**
     * 실행 <b>후</b> 클러스터 상태를 적는다. 여기서 "후"는 다시 잰 값이 아니라 이번 실행의 예약을 반영한
     * 값이다 — 배치 직후에 메트릭을 다시 부르면 실행이 그만큼 길어지고, 설비가 반응하기 전이라 숫자도
     * 달라지지 않는다.
     */
    private void recordSnapshotAfter(ScheduleRun run, PlacementContext context) {
        try {
            RunClusterSnapshot.Moving moving =
                    clusterSnapshot.movingOf(context, properties.getPower().getActiveCpuPercent());
            run.recordSnapshotAfter(moving.activeNodes(), moving.reclaimableWatts(), moving.avgTemperatureCelsius());
        } catch (RuntimeException e) {
            log.warn("실행 후 클러스터 스냅샷 기록 실패(배치는 계속한다): run={}", run.getId(), e);
            run.markSnapshotFailed();
        }
    }

    // ── 단계별 ─────────────────────────────────────────────────

    /**
     * 실행 권한을 잡는다. 자동 실행과 즉시 실행이 겹치면 한쪽만 진행한다.
     * 앱이 한 대라 DB 의 조건부 UPDATE 로 충분하다.
     */
    private void acquire(Schedule schedule, ScheduleRun.TriggerType triggerType,
                         OffsetDateTime startedAt) {
        int updated = scheduleRepository.acquireForRun(
                schedule.getId(), ScheduleRun.RunStatus.RUNNING, startedAt);
        if (updated == 0) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "이미 실행 중인 스케줄입니다: " + schedule.getScheduleId());
        }
        log.debug("스케줄 실행 권한 획득: schedule={}, trigger={}", schedule.getScheduleId(), triggerType);
    }

    /**
     * 배치할 워크로드를 고른다.
     *
     * <p>정렬을 SQL 에 맡기지 않는다. 우선순위가 문자열로 저장돼 있어 {@code order by} 로는
     * 사전순(CRITICAL, HIGH, LOW, NORMAL)이 되어 NORMAL 이 LOW 보다 뒤로 간다.</p>
     */
    List<Workload> findTargets(Schedule schedule) {
        Workload.ResourceType type = schedule.resolveResourceType();
        boolean matchNullType = type == Workload.ResourceType.VM;   // 기존 행은 VM 으로 해석한다

        return workloadRepository.findSchedulingCandidates(
                        TARGET_STATUSES, type, matchNullType, properties.getMaxRetryCount(),
                        retryableBefore())
                .stream()
                .sorted(orderFor(schedule))
                .toList();
    }

    /**
     * 이 시각 이전에 실패한 워크로드만 다시 시도한다.
     *
     * <p>간격이 0 이하면 아주 먼 미래를 돌려주어 조건이 사실상 사라진다 — 설정 하나로 이전
     * 동작으로 돌아간다.</p>
     */
    private OffsetDateTime retryableBefore() {
        int minutes = properties.getRetryBackoffMinutes();
        return minutes > 0 ? OffsetDateTime.now().minusMinutes(minutes) : OffsetDateTime.now().plusYears(1);
    }

    /**
     * 대상이 없을 때의 메시지.
     * 재시도 상한에 걸려 빠진 건수를 함께 적는다 — 그러지 않으면 "할 일이 없어서"인지
     * "전부 소진되어서"인지 구분할 수 없다.
     */
    private EmptyTargetOutcome emptyTargetOutcome(Schedule schedule) {
        Workload.ResourceType type = schedule.resolveResourceType();
        boolean matchNullType = type == Workload.ResourceType.VM;
        long exhausted = workloadRepository.countRetryExhausted(
                Workload.WorkloadStatus.FAILED, type, matchNullType, properties.getMaxRetryCount());
        if (exhausted > 0) {
            return new EmptyTargetOutcome(ScheduleRun.RunStatus.FAILED, String.format(
                    "대상 없음 (재시도 소진 %d건 — 일괄 재개가 필요합니다)", exhausted));
        }
        long waiting = workloadRepository.countRetryWaiting(
                TARGET_STATUSES, type, matchNullType, properties.getMaxRetryCount(), retryableBefore());
        if (waiting > 0) {
            return new EmptyTargetOutcome(ScheduleRun.RunStatus.COMPLETED, String.format(
                    "대상 없음 (재시도 대기 %d건 — %d분 뒤 다시 시도합니다)",
                    waiting, properties.getRetryBackoffMinutes()));
        }
        return new EmptyTargetOutcome(ScheduleRun.RunStatus.COMPLETED, "대상 없음");
    }

    /**
     * 대상이 0건일 때의 결과.
     *
     * <p>세 경우를 가른다. <b>소진</b>은 사람이 되살리기 전에는 이 스케줄이 아무 일도 못 하므로
     * 실패로 적는다 — 그러지 않으면 스케줄이 영원히 초록불로 남아 화면에서 문제가 보이지 않는다.
     * <b>재시도 대기</b>는 시간이 지나면 저절로 풀리므로 정상이고, 얼마 뒤에 다시 시도하는지 적는다.
     * <b>할 일 없음</b>은 그냥 정상이다. 실행 상태 값 집합은 늘리지 않는다(FAILED·COMPLETED 그대로).</p>
     */
    private record EmptyTargetOutcome(ScheduleRun.RunStatus status, String message) {}

    /** 스케줄이 지정한 정책, 없으면 리소스 종류별 활성 정책. */
    private SchedulingPolicy resolvePolicy(Schedule schedule) {
        if (schedule.getPolicyId() != null) {
            SchedulingPolicy policy = policyRepository.findById(schedule.getPolicyId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                            "스케줄에 지정된 배치 정책을 찾을 수 없습니다: " + schedule.getPolicyId()));
            return applyOverrides(policy, schedule);
        }
        return applyOverrides(schedulingService.resolvePolicy(schedule.resolveResourceType()), schedule);
    }

    /**
     * 스케줄이 지정한 온도 한계를 이번 실행에만 적용한다.
     *
     * <p>저장된 정책을 고치지 않으려고 <b>영속되지 않는 사본</b>을 만들어 쓴다.
     * 정책은 여러 스케줄이 공유하므로 한 스케줄의 설정이 남으면 안 된다.</p>
     *
     * <p>사본은 정책의 <b>모든 판단 필드</b>를 옮겨야 한다. {@code affinityEnabled} 가 빠져 있어
     * 온도 한계를 지정한 스케줄에서는 MDC 링크 게이트가 조용히 꺼지던 결함이 있었다
     * (2026-09-02, 단계 4-② 설계 중 발견). 테스트 {@code ScheduleOverridePolicyCopyTest} 가 고정한다.</p>
     */
    SchedulingPolicy applyOverrides(SchedulingPolicy policy, Schedule schedule) {
        if (schedule.getMaxTemperatureCelsius() == null) {
            return policy;
        }
        SchedulingPolicy copy = new SchedulingPolicy(policy.getName(), policy.getDescription());
        copy.setResourceType(policy.getResourceType());
        copy.setDefaultEngine(policy.getDefaultEngine());
        copy.setWarningTemperatureCelsius(policy.getWarningTemperatureCelsius());
        copy.setCpuWeight(policy.getCpuWeight());
        copy.setMemoryWeight(policy.getMemoryWeight());
        copy.setPowerWeight(policy.getPowerWeight());
        copy.setTemperatureWeight(policy.getTemperatureWeight());
        copy.setNetworkWeight(policy.getNetworkWeight());
        copy.setMaxCpuUsagePercent(policy.getMaxCpuUsagePercent());
        copy.setMaxMemoryUsagePercent(policy.getMaxMemoryUsagePercent());
        copy.setMaxPowerUsagePercent(policy.getMaxPowerUsagePercent());
        copy.setPowerAwareEnabled(policy.isPowerAwareEnabled());
        copy.setTemperatureAwareEnabled(policy.isTemperatureAwareEnabled());
        copy.setAffinityEnabled(policy.isAffinityEnabled());
        copy.setMaxTemperatureCelsius(schedule.getMaxTemperatureCelsius());
        return copy;
    }

    /** 스케줄이 지정한 엔진, 없으면 정책 기본 엔진, 그것도 없으면 기본값. */
    private String resolveEngine(Schedule schedule, SchedulingPolicy policy) {
        if (schedule.getEngine() != null && !schedule.getEngine().isBlank()) {
            return schedule.getEngine();
        }
        if (policy.getDefaultEngine() != null && engineRegistry.contains(policy.getDefaultEngine())) {
            return policy.getDefaultEngine();
        }
        return EngineRegistry.DEFAULT_ENGINE_ID;
    }

    private ScheduleRun startRun(Schedule schedule, ScheduleRun.TriggerType triggerType,
                                 String triggeredBy, String engineId, SchedulingPolicy policy) {
        ScheduleRun run = new ScheduleRun(schedule.getId(), schedule.getScheduleId(),
                triggerType, triggeredBy);
        run.recordContext(engineId, policy.getName());
        return runRepository.save(run);
    }

    private ScheduleRun finishRun(Schedule schedule, ScheduleRun run, ScheduleRun.RunStatus status,
                                  OffsetDateTime startedAt, String message) {
        run.finish(status, message);
        ScheduleRun saved = runRepository.save(run);
        complete(schedule, status, startedAt, message);
        return saved;
    }

    /** 실행 결과 메시지의 컬럼 길이. 스케줄·실행 이력 두 컬럼이 같은 길이라 한 상수를 공유한다. */
    static final int MESSAGE_MAX = ScheduleRun.MESSAGE_MAX;

    static final String RECOVERED_MESSAGE = "앱 재기동으로 중단됨 — 실행 권한을 회수했습니다";

    /**
     * 기동 시 남아 있는 실행 권한을 회수한다.
     *
     * <p>실행 권한은 실행이 끝나며 풀린다. 그런데 <b>실행 도중 프로세스가 죽으면</b>(우리 배포 절차인
     * 재시작이 곧 그 경우다) 표시가 그대로 남고, 그 스케줄은 자동·수동 실행도 삭제도 영구히 거부된다.
     * 다시 뜰 때 한 번 훑어 정리하는 것이 유일한 회수 수단이다.</p>
     *
     * <p><b>대상은 실행 권한이 잡힌 스케줄로만 한정한다.</b> 예정 시각이 지난 스케줄 전체를 건드리면
     * "앱이 꺼져 있던 사이의 실행은 몰아 돌리지 않는다"는 기존 계약이 깨진다. 실행 이력도 <b>기존 행을
     * 마무리할 뿐</b> 새 행을 만들지 않는다.</p>
     *
     * <p>스케줄 하나가 실패해도 나머지는 회수한다 — 앱 기동을 막으면 안 되므로 호출자가 다시 감싼다.</p>
     *
     * <p><b>한계 — 앱이 한 대라는 전제 위에 있다.</b> 두 대가 떠 있으면 한쪽의 기동이 다른 쪽에서 <b>실제로 돌고 있는
     * 실행</b>을 중단된 것으로 보고 실패 처리한다. 무중단 배포나 다중 인스턴스로 가면 "이 프로세스가 잡은 실행"을
     * 구분할 표식(인스턴스 식별자)이 먼저 필요하다.</p>
     *
     * @return 회수한 스케줄 수
     */
    public int recoverStuckRuns() {
        List<Schedule> stuck = scheduleRepository.findByLastRunStatus(ScheduleRun.RunStatus.RUNNING);
        if (stuck.isEmpty()) return 0;

        List<Long> ids = stuck.stream().map(Schedule::getId).toList();
        List<ScheduleRun> runs = runRepository.findByScheduleIdInAndStatus(
                ids, ScheduleRun.RunStatus.RUNNING);
        recoverStuckWorkloads(runs);
        finishStuckRuns(runs);

        int recovered = 0;
        for (Schedule schedule : stuck) {
            try {
                // 그 사이 삭제된 스케줄이면 갱신 행이 0 이다 — 로그 건수가 어긋나지 않게 실제 결과를 센다.
                if (complete(schedule, ScheduleRun.RunStatus.FAILED, schedule.getLastRunAt(), RECOVERED_MESSAGE) > 0) {
                    recovered++;
                }
            } catch (RuntimeException e) {
                log.error("스케줄 실행 권한 회수 실패: schedule={}", schedule.getScheduleId(), e);
            }
        }
        log.warn("기동 회수: 실행 중으로 남아 있던 스케줄 {}건 중 {}건을 정리했습니다", stuck.size(), recovered);
        return recovered;
    }

    /** 중단된 실행이 배치한 워크로드의 예약을 풀어 다시 시도할 수 있게 한다. */
    private void recoverStuckWorkloads(List<ScheduleRun> runs) {
        if (runs.isEmpty()) return;
        try {
            List<Long> runIds = runs.stream().map(ScheduleRun::getId).toList();
            LinkedHashSet<Long> workloadIds = historyRepository.findByScheduleRunIdIn(runIds).stream()
                    .filter(history -> Boolean.TRUE.equals(history.getSuccess()))
                    .map(SchedulingHistory::getWorkloadId)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<Workload> recovered = new ArrayList<>();
            for (Workload workload : workloadRepository.findAllById(workloadIds)) {
                if (workload.getStatus() != Workload.WorkloadStatus.SCHEDULED) continue;
                workload.failDispatch(RECOVERED_MESSAGE);
                recovered.add(workload);
            }
            workloadRepository.saveAll(recovered);
            if (!recovered.isEmpty()) {
                log.warn("기동 회수: 중단된 실행에 배치된 워크로드 {}건을 실패로 되돌렸습니다", recovered.size());
            }
        } catch (RuntimeException e) {
            log.error("중단된 실행의 워크로드 복구 실패(실행 이력과 스케줄 권한 회수는 계속한다)", e);
        }
    }

    /** 끝나지 않은 실행 이력을 마무리한다. 실패해도 스케줄 권한 회수는 계속한다. */
    private void finishStuckRuns(List<ScheduleRun> runs) {
        try {
            runs.forEach(run -> run.finish(ScheduleRun.RunStatus.FAILED, RECOVERED_MESSAGE));
            runRepository.saveAll(runs);
        } catch (RuntimeException e) {
            log.error("중단된 실행 이력 정리 실패(스케줄 권한 회수는 계속한다)", e);
        }
    }

    /**
     * 최근 실행 정보를 기록하고 <b>실행 권한을 푼다</b>.
     *
     * <p>엔티티를 저장하지 않고 전용 UPDATE 하나만 쓴다 — 이유는
     * {@link ScheduleRepository#recordRunResult} 주석에 적었다. 조회용으로 들고 있는 엔티티에도 같은 값을
     * 반영해 호출자가 이어서 읽을 수 있게 하되, 그 엔티티를 저장하지는 않는다.</p>
     */
    int complete(Schedule schedule, ScheduleRun.RunStatus status,
                 OffsetDateTime startedAt, String message) {
        OffsetDateTime finishedAt = OffsetDateTime.now();
        String trimmed = trim(message);
        OffsetDateTime nextRunAt = nextRunAtOf(schedule, finishedAt);

        int updated = scheduleRepository.recordRunResult(
                schedule.getId(), status, startedAt, finishedAt, trimmed, nextRunAt);

        schedule.recordRun(status, startedAt, finishedAt, trimmed);
        schedule.setNextRunAt(nextRunAt);
        return updated;
    }

    /**
     * cron 을 읽을 수 없을 때 다음 실행 시각을 얼마나 뒤로 미룰지(분).
     *
     * <p><b>폴링 주기보다 훨씬 커야 한다.</b> 폴링 주기만큼만 밀면 "실행 → cron 실패 → 주기 뒤 재실행"이
     * 끝없이 도는 핫 루프가 된다. 그렇다고 비워 두면(null) 자동 실행이 조용히 멈춰 아무도 모른다.
     * 한 시간이면 사람이 로그를 보고 고칠 여유가 있으면서 루프도 아니다.</p>
     */
    static final int CRON_FALLBACK_MINUTES = 60;

    /**
     * 다음 실행 시각.
     *
     * <p><b>DB 의 현재 값으로 계산한다.</b> 넘겨받은 엔티티는 실행이 <b>시작될 때</b> 읽은 사본이라,
     * 실행 도중 사용자가 cron 이나 자동 실행 여부를 바꿨으면 낡았다. 그 사본으로 계산하면 방금 켠 자동 실행이
     * {@code nextRunAt = null} 로 덮여 <b>그 스케줄만 조용히 안 돌게</b> 된다(폴러는 null 을 건너뛴다).
     * 화면에는 자동 실행이 켜져 보이므로 원인을 찾기도 어렵다.</p>
     *
     * <p>cron 을 못 읽어도 <b>실행 권한 해제를 막지 않는다</b> — 그 경우 넉넉히 뒤로 밀어 폴러가 같은 실행을
     * 반복해 던지지 않게 한다.</p>
     */
    private OffsetDateTime nextRunAtOf(Schedule schedule, OffsetDateTime base) {
        String cron = scheduleRepository.findCronExpressionById(schedule.getId())
                .orElseGet(schedule::getCronExpression);
        boolean autoRunnable = scheduleRepository.findAutoRunnableById(schedule.getId())
                .orElseGet(schedule::isAutoRunnable);
        try {
            return scheduleService.nextRunAt(cron, autoRunnable);
        } catch (RuntimeException e) {
            log.warn("다음 실행 시각 계산 실패 — {}분 뒤로 민다(cron 을 고쳐야 정상 주기로 돌아온다): schedule={}, cron={}",
                    CRON_FALLBACK_MINUTES, schedule.getScheduleId(), cron, e);
            return autoRunnable ? base.plusMinutes(CRON_FALLBACK_MINUTES) : null;
        }
    }

    /** 컬럼 길이에 맞춰 자른다. 자르지 않으면 긴 예외 메시지 하나로 상태 기록이 통째로 실패한다. */
    static String trim(String message) {
        return ScheduleRun.trim(message);
    }

    /**
     * 실패로 마무리한다. 여기서 다시 예외가 나면 원래 예외를 가리므로 삼킨다.
     * 상태를 못 풀면 스케줄이 RUNNING 으로 남아 영영 실행되지 않는다.
     *
     * <p><b>이력 저장과 권한 해제를 따로 감싼다.</b> 한 덩어리로 두면 이력 저장이 먼저 터졌을 때 해제가
     * 아예 실행되지 않는다 — 실제로 이것이 가장 유력한 잠금 경로였다(긴 오류 메시지 → 컬럼 길이 초과).</p>
     */
    private void failQuietly(Schedule schedule, ScheduleRun run,
                             OffsetDateTime startedAt, String message) {
        String trimmed = trim(message);
        try {
            if (run != null) {
                run.finish(ScheduleRun.RunStatus.FAILED, trimmed);
                runRepository.save(run);
            }
        } catch (RuntimeException e) {
            log.error("실행 이력 저장 실패(권한 해제는 계속한다): schedule={}", schedule.getScheduleId(), e);
        }
        try {
            complete(schedule, ScheduleRun.RunStatus.FAILED, startedAt, trimmed);
        } catch (RuntimeException e) {
            log.error("스케줄 실행 상태 정리 실패: schedule={}", schedule.getScheduleId(), e);
        }
    }
}

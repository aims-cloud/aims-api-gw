package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.ScheduleDryRunResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleRunDetailResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunPreviewResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.PolicyChoice;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunInputRepository;
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
 * 수행     노드 스냅샷 1회 → 예약 반영 → 워크로드마다 배치 직전 다시 읽기(지워졌거나 대기·실패가 아니면
 *          건너뜀 — 실패로 셈) → 제약→엔진→배치(저장 직전에도 대기·실패일 때만 저장 · 아니면 건너뜀)
 *          → 커밋 뒤 예약 누적
 *
 * 판정     대상 0건 · AUTO      → 실행 이력을 만들지 않고 최근 실행 정보만 갱신
 *          대상 0건 · MANUAL    → 소진이면 FAILED(사유에 일괄 재개 필요), 아니면 COMPLETED "대상 없음"
 *          성공 1건 이상         → COMPLETED
 *          대상은 있는데 전부 실패 → FAILED
 *          활성 노드 0건 · 예외   → FAILED
 * </pre>
 *
 * <p>트랜잭션을 이 클래스에 걸지 않는다. 노드 수집이 외부 메트릭 저장소를 호출해 오래
 * 걸리기 때문이다. DB 쓰기는 리포지토리와 {@link PlacementService} 의 짧은 트랜잭션에
 * 맡긴다. 예외는 노드를 모으지 않는 {@link #preview} 하나로, 쓰기를 막는 목적의 읽기 전용 트랜잭션을 건다.</p>
 */
@Service
public class ScheduleExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleExecutionService.class);

    /**
     * 실행 대상으로 삼을 워크로드 상태. 실패한 것도 다시 시도한다(재시도 상한 안에서). 배치 저장 직전의 조건부 갱신
     * ({@link PlacementService})도 같은 목록을 쓴다.
     */
    static final List<Workload.WorkloadStatus> TARGET_STATUSES =
            List.of(Workload.WorkloadStatus.PENDING, Workload.WorkloadStatus.FAILED);

    /** 우선순위 높은 것부터, 같으면 먼저 등록된 것부터(현행 기본). */
    static final Comparator<Workload> BY_PRIORITY_THEN_AGE =
            Comparator.comparingInt((Workload w) -> w.getPriority().ordinal()).reversed()
                    .thenComparing(Workload::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * 우선순위 높은 것부터, 같으면 요구 CPU → 요구 메모리가 큰 것부터, 그래도 같으면 먼저 등록된 것부터.
     * 빈 패킹의 first-fit-decreasing 에서 가져온 선택지다. 기본 엔진(weighted)에서 조각화를 줄이는지는 배치 전략 비교의
     * 스케줄 배치 순서 열로 합성 판에서만 쟀고 전력 엔진은 재지 않았다(docs/14). 우선순위가 첫 기준이라 공정성은 그대로다.
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
    /** 재시작 정리에서 전달 기록을 보는 데 쓴다. 생성자로 직접 조립해 null 을 넣으면(지금은 그런 조립이 없다) 전달 기록 없이 전부 실패로 돌린다. */
    private final PlacementDispatchService dispatchService;
    /** 실행마다 배치 입력을 남긴다. 빈이 아니라 여기서 만든다. */
    private final RunInputCapture inputCapture;

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
                                    RunClusterSnapshot clusterSnapshot,
                                    PlacementDispatchService dispatchService,
                                    ScheduleRunInputRepository inputRepository,
                                    ThermalModel thermalModel) {
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
        this.dispatchService = dispatchService;
        this.inputCapture = new RunInputCapture(inputRepository, thermalModel, properties);
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
            SchedulingPolicy policy = policyChoiceFor(schedule, true).policy();
            String engineId = engineChoiceFor(schedule, policy).engine();
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
                run.markSnapshotNoTarget();   // 노드를 모으기 전에 끝난다 — 평범한 회차다
                return finishRun(schedule, run, outcome.status(), startedAt, message);
            }

            run = startRun(schedule, triggerType, triggeredBy, engineId, policy);

            PlacementContext context = schedulingService.createContext(policy)
                    .withPowerBudget(schedule.getMaxPowerBudgetWatts());
            if (context.isEmpty()) {
                run.recordCounts(targets.size(), 0, 0, truncated);
                run.markSnapshotNoNodes();
                return finishRun(schedule, run, ScheduleRun.RunStatus.FAILED, startedAt,
                        SchedulingService.noActiveNodesReason(context));
            }

            recordSnapshotBefore(run, context);
            captureInputs(run, context, targets, engineId);

            int success = 0;
            int attempted = 0;
            String firstFailure = null;
            try {
                for (Workload polled : targets) {
                    // 대상은 실행 시작 때 읽은 사본이라 노드 수집과 앞 워크로드의 배치·전달을 도는 사이 낡는다.
                    // 그 사본으로 저장하면 그 사이 커밋된 편집·되살리기가 지워지고, 지운 워크로드는 회차를 끊는다.
                    // 그래서 배치 직전에 다시 읽어, 지워졌거나 대기·실패가 아니면 이번 회차에서 건너뛴다.
                    Workload workload = workloadRepository.findById(polled.getId()).orElse(null);
                    String skip = skipReasonOf(workload);
                    if (skip != null) {
                        placementService.recordSkip(polled, context, engineId, run.getId(), skip);
                        attempted++;
                        if (firstFailure == null) firstFailure = skip;
                        continue;
                    }
                    SchedulingResult placed = placementService.place(workload, context, engineId, run.getId());
                    if (placed.success()) {
                        // 예약은 결정이 커밋된 뒤에 잡는다 — 배치 안에서 잡으면 저장·커밋이 실패해도 예약이 남아
                        // 그 예외로 루프가 끝난 뒤 남기는 실행 후 스냅샷에 저장되지 않은 배치가 반영된다.
                        context.reserve(placed.selectedNodeId(), workload);
                    }
                    SchedulingResult result = schedulingService.dispatch(workload, placed, run.getId());
                    if (placed.success() && !result.success()) {
                        // 실행 계층이 받지 않은 배치 — DB 쪽은 디스패치가 이미 FAILED 로 돌렸지만, 이 실행의 컨텍스트에
                        // 잡힌 예약은 여기서 되돌려야 같은 실행의 다음 워크로드와 실행 후 스냅샷이 없는 예약을 보지 않는다.
                        context.unreserve(placed.selectedNodeId(), workload);
                    }
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

            return finishRun(schedule, run, success == 0 ? ScheduleRun.RunStatus.FAILED : ScheduleRun.RunStatus.COMPLETED,
                    startedAt, resultMessage(targets.size(), success, truncated, firstFailure));

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
     * 배치 직전에 다시 읽은 워크로드를 이번 회차에서 건너뛸 사유. 지워졌으면 삭제 사유, 상태가 대기·실패가 아니면
     * (다른 경로로 배치됐거나 실행 중·완료로 바뀜) 상태 사유, 배치해도 되면 {@code null}. 건너뛴 건은 실패로 세고
     * 워크로드는 쓰지 않는다(재시도 횟수를 올리지 않음).
     */
    static String skipReasonOf(Workload fresh) {
        if (fresh == null) return "배치 건너뜀 — 대상 조회 뒤 삭제됨";
        if (!TARGET_STATUSES.contains(fresh.getStatus())) {
            return "배치 건너뜀 — 대상 조회 뒤 상태가 " + fresh.getStatus() + " 로 바뀜";
        }
        return null;
    }

    /** 배치 루프가 끝난 실행이 남기는 문구 — 모의 실행도 같은 함수를 써서 글자 단위로 같다. */
    static String resultMessage(int targets, int success, boolean truncated, String firstFailure) {
        if (success == 0) {
            return String.format("대상 %d건 전부 배치 실패 — %s", targets, firstFailure);
        }
        return String.format("대상 %d건 / 성공 %d건 / 실패 %d건%s",
                targets, success, targets - success, truncated ? " (상한 초과분은 다음 실행으로)" : "");
    }

    // ── 모의 실행 ───────────────────────────────────────────────

    /**
     * 스케줄을 지금 실행하면 어떻게 배치될지를 <b>저장 없이</b> 돌려준다.
     *
     * <p>실행과 같은 코드로 정책·엔진·대상·순서·상한·전력 상한을 해석하고, 컨텍스트 하나 위에 대상을 차례로 놓아 본다 —
     * 성공한 건의 요구량을 예약으로 누적하므로 앞 건이 뒤 건에 보인다(실행의 배치 루프가 {@code place} 에 넘기는 것과 같은 입력).
     * 실행 권한을 잡지 않고(실행 중·사용 안 함 스케줄도 200), 실행 계층에 전달하지 않으며(전부 받는다고 본다), 워크로드·
     * 배치 이력·전달·실행 이력·{@code default} 정책 어느 행도 쓰지 않는다. 트랜잭션은 두지 않는다 — 메트릭 수집이 길다.</p>
     *
     * <p>평가 중 예외가 난 건은 그 건만 실패(사유 = 예외 문구)로 두고 계속한다 — 모의는 결과를 보려는 호출이라
     * 한 건 때문에 전체를 잃지 않는다.</p>
     */
    public ScheduleDryRunResponse dryRun(Long id) {
        return dryRunOf(scheduleService.require(id), false);
    }

    /**
     * 저장하지 않은 생성 폼으로 같은 모의 실행 — 저장 전 미리보기와 같은 검증을 거친 초안을 그대로 놓아 본다.
     *
     * <p>전력 상한과 온도 상한은 폼 값을 그대로 적용한다. 온도 상한을 지정하면 냉각 대기를 새로 걸지 않는 평가기로
     * 평가한다 — 폼의 온도 상한은 저장하지 않은 값이라, 그 상한으로 냉각 대기(그 상한 이하로 평가하는 배치를 막음)를
     * 남기면 안 된다. 이미 걸린 냉각 대기는 상한별로 그대로 본다. 지정하지 않으면 저장된 스케줄의 모의 실행과 같다.</p>
     */
    public ScheduleDryRunResponse dryRunDraft(ScheduleRequest request) {
        Schedule draft = scheduleService.draft(request);
        return dryRunOf(draft, draft.getMaxTemperatureCelsius() != null);
    }

    /**
     * @param withoutCooldownMarking 참이면 수집한 컨텍스트의 사본({@link PlacementContext#sandbox})으로 평가해 냉각 대기를
     *                               새로 걸지 않는다. 수집 제외 목록은 사본이 잇지 않아 따로 옮긴다.
     */
    private ScheduleDryRunResponse dryRunOf(Schedule schedule, boolean withoutCooldownMarking) {
        PolicyChoice policy = policyChoiceFor(schedule, false);
        EngineChoice engine = engineChoiceFor(schedule, policy.policy());
        Workload.ResourceType type = schedule.resolveResourceType();

        List<Workload> targets = findTargets(schedule);
        boolean truncated = targets.size() > properties.getMaxWorkloadsPerRun();
        if (truncated) {
            targets = targets.subList(0, properties.getMaxWorkloadsPerRun());
        }
        String knobs = clusterSnapshot.knobsDigest();

        if (targets.isEmpty()) {
            EmptyTargetOutcome outcome = emptyTargetOutcome(schedule);
            return response(schedule, type, policy, engine, outcome.status(), outcome.message(), outcome.reason(),
                    0, 0, false, emptySnapshot(ScheduleRun.SnapshotStatus.NO_TARGET, knobs), List.of());
        }

        PlacementContext collected = schedulingService.createContext(policy.policy())
                .withPowerBudget(schedule.getMaxPowerBudgetWatts());
        PlacementContext context = withoutCooldownMarking
                ? collected.sandbox(collected.nodes()).withExcluded(collected.excludedNodes())
                : collected;
        if (context.isEmpty()) {
            return response(schedule, type, policy, engine, ScheduleRun.RunStatus.FAILED,
                    SchedulingService.noActiveNodesReason(context), null,
                    targets.size(), 0, truncated, emptySnapshot(ScheduleRun.SnapshotStatus.NO_NODES, knobs), List.of());
        }

        RunClusterSnapshot.Fixed fixed = clusterSnapshot.fixedOf(context);
        RunClusterSnapshot.Moving before = clusterSnapshot.movingOf(context, properties.getPower().getActiveCpuPercent());

        List<ScheduleDryRunResponse.DryRunDecision> decisions = new ArrayList<>(targets.size());
        int success = 0;
        String firstFailure = null;
        for (Workload workload : targets) {
            SchedulingResult result;
            try {
                result = schedulingService.evaluateIn(workload, engine.engine(), context);
            } catch (RuntimeException | LinkageError e) {
                log.warn("모의 실행 평가 실패(계속한다): schedule={}, workload={}", schedule.getScheduleId(), workload.getId(), e);
                result = SchedulingResult.failure("평가 중 오류: " + e.getMessage(), policy.policy().getName());
            }
            if (result.success()) {
                context.reserve(result.selectedNodeId(), workload);
                success++;
            } else if (firstFailure == null) {
                firstFailure = result.reason();
            }
            decisions.add(ScheduleDryRunResponse.DryRunDecision.of(workload, result, PlacementService.exclusionCodesOf(result)));
        }

        RunClusterSnapshot.Moving after = clusterSnapshot.movingOf(context, properties.getPower().getActiveCpuPercent());
        ScheduleRunDetailResponse.ClusterSnapshot snapshot = new ScheduleRunDetailResponse.ClusterSnapshot(
                ScheduleRun.SnapshotStatus.OK, knobs,
                fixed.nodeCount(), fixed.excludedCount(), fixed.measuredNodes(), fixed.measuredPowerWatts(),
                before.activeNodes(), after.activeNodes(),
                before.reclaimableWatts(), after.reclaimableWatts(),
                before.avgTemperatureCelsius(), after.avgTemperatureCelsius());
        return response(schedule, type, policy, engine,
                success == 0 ? ScheduleRun.RunStatus.FAILED : ScheduleRun.RunStatus.COMPLETED,
                resultMessage(targets.size(), success, truncated, firstFailure), null,
                targets.size(), success, truncated, snapshot, decisions);
    }

    private static ScheduleRunDetailResponse.ClusterSnapshot emptySnapshot(ScheduleRun.SnapshotStatus status, String knobs) {
        return new ScheduleRunDetailResponse.ClusterSnapshot(status, knobs, null, null, null, null, null, null, null, null, null, null);
    }

    private static ScheduleDryRunResponse response(Schedule schedule, Workload.ResourceType type, PolicyChoice policy,
                                                   EngineChoice engine, ScheduleRun.RunStatus status, String message,
                                                   ScheduleRunPreviewResponse.EmptyReason emptyReason, int targets, int success,
                                                   boolean truncated, ScheduleRunDetailResponse.ClusterSnapshot snapshot,
                                                   List<ScheduleDryRunResponse.DryRunDecision> decisions) {
        return new ScheduleDryRunResponse(
                schedule.getId(), schedule.getScheduleId(), schedule.isEnabled(), type,
                policy.policy().getName(), policy.source(), engine.engine(), engine.source(),
                status, ScheduleRun.trim(message), emptyReason,
                targets, success, targets - success, truncated, snapshot, decisions);
    }

    /**
     * 실행 <b>전</b> 클러스터 상태를 실행 이력에 적는다.
     *
     * <p>계산이 터져도 <b>실행은 계속한다</b>. 스냅샷은 곁들이는 기록이지 배치의 일부가 아니어서, 여기서
     * 예외를 밖으로 내보내면 잘 돌던 배치가 통째로 실패로 뒤바뀐다. 대신 상태를 실패로 남겨 빈 칸이
     * "안 쟀다"인지 "재다 터졌다"인지 구분되게 한다.</p>
     *
     * <p>{@link LinkageError} 까지 잡는다. 배포가 실행 중인 JVM 의 jar 를 덮어쓰는 이 환경에서는 아직
     * 로드되지 않은 클래스를 그때 읽어 {@code NoClassDefFoundError} 가 난다 — 이 계산이 쓰는 record 와
     * enum 이 바로 그런 지연 로드 대상이다. {@code RuntimeException} 만 잡으면 "실행은 계속한다"는 이
     * 메서드의 약속이 하필 그 상황에서 깨진다. 메모리 고갈 같은 나머지 {@code Error} 는 그대로 내보낸다.</p>
     *
     * <p>여기서 <b>한 번 저장한다</b>. 저장하지 않으면 이 값이 실행이 끝날 때까지 DB 에 닿지 않아, 배치
     * 도중 앱이 죽은 회차가 "전만 기록됨"이 아니라 아무것도 기록되지 않은 행으로 남는다 — 기동 회수는
     * 실행 이력을 마무리할 뿐 스냅샷을 채우지 못한다. 실행 1회당 UPDATE 한 번이고 배치 루프 밖이다.</p>
     */
    private void recordSnapshotBefore(ScheduleRun run, PlacementContext context) {
        try {
            RunClusterSnapshot.Fixed fixed = clusterSnapshot.fixedOf(context);
            RunClusterSnapshot.Moving moving =
                    clusterSnapshot.movingOf(context, properties.getPower().getActiveCpuPercent());
            run.recordSnapshotBefore(clusterSnapshot.knobsDigest(),
                    fixed.nodeCount(), fixed.excludedCount(), fixed.measuredNodes(),
                    fixed.measuredPowerWatts(),
                    moving.activeNodes(), moving.reclaimableWatts(), moving.avgTemperatureCelsius());
            runRepository.save(run);
        } catch (RuntimeException | LinkageError e) {
            log.warn("실행 전 클러스터 스냅샷 기록 실패(배치는 계속한다): run={}", run.getId(), e);
            run.markSnapshotFailed();
        }
    }

    /**
     * 배치 루프 직전의 입력을 남긴다({@link RunInputCapture}). 스냅샷과 같은 규칙이다 — 곁들이는 기록이라 실패해도 배치는
     * 계속하고, 지연 로드 실패({@link LinkageError})까지 잡는다. 실행 1회당 INSERT 한 번이고 배치 루프 밖이다.
     */
    private void captureInputs(ScheduleRun run, PlacementContext context, List<Workload> targets, String engineId) {
        try {
            inputCapture.capture(run.getId(), engineId, context, targets, clusterSnapshot.knobsDigest());
        } catch (RuntimeException | LinkageError e) {
            log.warn("실행 입력 저장 실패(배치는 계속한다): run={}", run.getId(), e);
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
        } catch (RuntimeException | LinkageError e) {
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
     * 스케줄을 <b>지금 실행하면</b> 무엇이 적용되고 몇 건을 다루게 되는지를 저장 없이 계산한다.
     *
     * <p>실행({@link #execute})과 같은 메서드로 정책·엔진을 해석하고 같은 질의로 대상·대기·소진을 세어 두 응답이
     * 어긋나지 않는다. 다만 실행 권한({@code acquireForRun})을 잡지 않고, 노드를 모으지 않으며(외부 메트릭 호출 0),
     * {@code default} 정책이 없어도 만들지 않는다. 사용 여부가 꺼진 스케줄과 실행 중인 스케줄도 읽기라 200 이다.</p>
     *
     * <p>대상 건수는 실행이 <b>시도할</b> 건수다 — 실제로 배치될 건수는 노드 상태에 달려 있어 여기서는 알 수 없다.
     * 대기·소진 건수는 재시도 간격 경계를 지나며 바뀌므로 호출 시각의 값이다.</p>
     */
    @Transactional(readOnly = true)
    public ScheduleRunPreviewResponse preview(Long id) {
        return previewOf(scheduleService.require(id));
    }

    /**
     * 저장 전 폼 값으로 보는 미리보기 — 저장된 스케줄의 미리보기와 같은 해석·같은 질의이고, 스케줄을 만들지 않는다.
     *
     * <p>응답 값을 바꾸는 칸은 셋이다 — 리소스 종류·정책·엔진. 온도 상한·배치 순서는 실행과 같은 해석에 쓰이지만
     * 응답에는 드러나지 않는다(정책 이름은 사본이어도 같고, 정렬은 건수를 바꾸지 않는다). 전력 상한은 노드를 모은 뒤
     * 노드 단위로 거는 제약이라 건수에 닿지 않고(저장된 미리보기도 같다), 스케줄명·실행 주기·자동 실행은
     * 실행 해석과 무관하다. {@code id} 는 없으니 {@code null} 이다.</p>
     */
    @Transactional(readOnly = true)
    public ScheduleRunPreviewResponse previewDraft(ScheduleRequest request) {
        return previewOf(scheduleService.draft(request));
    }

    private ScheduleRunPreviewResponse previewOf(Schedule schedule) {
        PolicyChoice policy = policyChoiceFor(schedule, false);
        EngineChoice engine = engineChoiceFor(schedule, policy.policy());

        Workload.ResourceType type = schedule.resolveResourceType();
        long targets = findTargets(schedule).size();
        long exhausted = countExhausted(type);
        long waiting = countWaiting(type);
        EmptyTargetOutcome empty = targets == 0 ? outcomeOf(exhausted, () -> waiting) : null;

        return new ScheduleRunPreviewResponse(
                schedule.getId(), schedule.getScheduleId(), schedule.isEnabled(), type,
                policy.policy().getName(), policy.source(),
                engine.engine(), engine.source(),
                targets, properties.getMaxWorkloadsPerRun(), waiting, exhausted,
                empty == null ? null : empty.reason(),
                empty == null ? null : empty.message());
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
        // 소진이 0 일 때만 대기를 센다 — 실행 경로의 조회 횟수를 예전과 같게 둔다.
        return outcomeOf(countExhausted(type), () -> countWaiting(type));
    }

    /** 재시도 상한에 걸린 워크로드 수. 대상 선정({@link #findTargets})과 같은 종류 규칙(VM 은 종류 빈 행 포함). */
    private long countExhausted(Workload.ResourceType type) {
        return workloadRepository.countRetryExhausted(
                Workload.WorkloadStatus.FAILED, type, type == Workload.ResourceType.VM, properties.getMaxRetryCount());
    }

    /** 재시도 간격이 아직 지나지 않은 워크로드 수. 대상 선정과 같은 종류·상태·간격 규칙. */
    private long countWaiting(Workload.ResourceType type) {
        return workloadRepository.countRetryWaiting(
                TARGET_STATUSES, type, type == Workload.ResourceType.VM, properties.getMaxRetryCount(), retryableBefore());
    }

    /**
     * 대상 0건의 사유와 문구를 건수만으로 정한다. 실행과 미리보기가 <b>이 한 곳</b>에서 문구를 얻으므로 미리보기가
     * 보여 준 문장과 실행이 남기는 {@code lastRunMessage} 가 글자 단위로 같다.
     */
    EmptyTargetOutcome outcomeOf(long exhausted, LongSupplier waitingSupplier) {
        if (exhausted > 0) {
            return new EmptyTargetOutcome(ScheduleRun.RunStatus.FAILED, String.format(
                    "대상 없음 (재시도 소진 %d건 — 일괄 재개가 필요합니다)", exhausted),
                    ScheduleRunPreviewResponse.EmptyReason.EXHAUSTED);
        }
        long waiting = waitingSupplier.getAsLong();
        if (waiting > 0) {
            return new EmptyTargetOutcome(ScheduleRun.RunStatus.COMPLETED, String.format(
                    "대상 없음 (재시도 대기 %d건 — %d분 뒤 다시 시도합니다)",
                    waiting, properties.getRetryBackoffMinutes()),
                    ScheduleRunPreviewResponse.EmptyReason.WAITING);
        }
        return new EmptyTargetOutcome(ScheduleRun.RunStatus.COMPLETED, "대상 없음",
                ScheduleRunPreviewResponse.EmptyReason.NONE);
    }

    /**
     * 대상이 0건일 때의 결과.
     *
     * <p>세 경우를 가른다. <b>소진</b>은 사람이 되살리기 전에는 이 스케줄이 아무 일도 못 하므로
     * 실패로 적는다 — 그러지 않으면 스케줄이 영원히 초록불로 남아 화면에서 문제가 보이지 않는다.
     * <b>재시도 대기</b>는 시간이 지나면 저절로 풀리므로 정상이고, 얼마 뒤에 다시 시도하는지 적는다.
     * <b>할 일 없음</b>은 그냥 정상이다. 실행 상태 값 집합은 늘리지 않는다(FAILED·COMPLETED 그대로).</p>
     */
    record EmptyTargetOutcome(ScheduleRun.RunStatus status, String message,
                              ScheduleRunPreviewResponse.EmptyReason reason) {}

    /**
     * 스케줄이 지정한 정책, 없으면 리소스 종류별 활성 정책 — 출처와 함께.
     *
     * @param persistDefault ④ 단계에서 {@code default} 행이 없을 때 만들어 저장할지. 실행은 true(이력이 정책 이름을
     *                       가리켜야 한다), 미리보기는 false(읽기 요청이 행을 만들면 안 된다)
     */
    private PolicyChoice policyChoiceFor(Schedule schedule, boolean persistDefault) {
        if (schedule.getPolicyId() != null) {
            SchedulingPolicy policy = policyRepository.findById(schedule.getPolicyId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                            "스케줄에 지정된 배치 정책을 찾을 수 없습니다: " + schedule.getPolicyId()));
            return new PolicyChoice(applyOverrides(policy, schedule), PolicyChoice.Source.SCHEDULE, true);
        }
        Workload.ResourceType type = schedule.resolveResourceType();
        PolicyChoice choice = persistDefault
                ? schedulingService.resolvePolicyChoice(type) : schedulingService.choosePolicy(type);
        return choice.withPolicy(applyOverrides(choice.policy(), schedule));
    }

    /**
     * 스케줄이 지정한 온도 한계를 이번 실행에만 적용한다.
     *
     * <p>저장된 정책을 고치지 않으려고 <b>영속되지 않는 사본</b>을 만들어 쓴다.
     * 정책은 여러 스케줄이 공유하므로 한 스케줄의 설정이 남으면 안 된다.</p>
     *
     * <p>사본은 {@link SchedulingPolicy#detachedCopy()} 가 만든다 — 판단 필드 전부를 옮기는 곳은 그 메서드 하나다.
     * 전에는 여기서 필드를 손으로 옮기다 {@code affinityEnabled} 가 빠져 온도 한계를 지정한 스케줄에서만 MDC 링크
     * 게이트가 조용히 꺼지던 결함이 있었다(2026-09-02). 테스트 {@code ScheduleOverridePolicyCopyTest} 가 고정한다.</p>
     */
    SchedulingPolicy applyOverrides(SchedulingPolicy policy, Schedule schedule) {
        if (schedule.getMaxTemperatureCelsius() == null) {
            return policy;
        }
        SchedulingPolicy copy = policy.detachedCopy();
        copy.setMaxTemperatureCelsius(schedule.getMaxTemperatureCelsius());
        return copy;
    }

    /** 스케줄이 지정한 엔진, 없으면 정책 기본 엔진(등록된 것일 때만), 그것도 없으면 서버 기본값. 규칙은 단건 배치와 같은 {@link EngineChoice}. */
    private EngineChoice engineChoiceFor(Schedule schedule, SchedulingPolicy policy) {
        return EngineChoice.choose(schedule.getEngine(), policy, engineRegistry);
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
     * <p><b>중단된 실행이 배치한 워크로드는 전달 기록을 보고 가른다.</b> 실행 계층이 이미 받은 것(기록 ACCEPTED·RUNNING)은
     * 그대로 두고, 전달 중이던 것(REQUESTED)은 기록을 닫으며 실패로 돌리고, 기록이 없거나 옛 결정의 기록뿐이거나 이미 끝난 기록이면 실패로
     * 돌린다({@link PlacementDispatchService#recoverAfterRestart}). 실행 이력과 실행 권한 정리는 이와 무관하게 그대로다.</p>
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

    /**
     * 중단된 실행이 배치한 워크로드를 전달 기록에 맞춰 정리한다 — 실행 계층이 이미 받은 것은 그대로 두고, 그 밖은
     * 예약을 풀어 다시 시도할 수 있게 한다. 전달 서비스 없이 직접 조립했을 때는(지금은 없다) 전부 실패로 돌린다.
     */
    private void recoverStuckWorkloads(List<ScheduleRun> runs) {
        if (runs.isEmpty()) return;
        try {
            List<Long> runIds = runs.stream().map(ScheduleRun::getId).toList();
            LinkedHashSet<Long> workloadIds = historyRepository.findByScheduleRunIdIn(runIds).stream()
                    .filter(history -> Boolean.TRUE.equals(history.getSuccess()))
                    .map(SchedulingHistory::getWorkloadId)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            int kept = 0, closed = 0, failed = 0;
            List<Workload> fallback = new ArrayList<>();
            for (Workload workload : workloadRepository.findAllById(workloadIds)) {
                if (workload.getStatus() != Workload.WorkloadStatus.SCHEDULED) continue;
                if (dispatchService == null) {
                    workload.failDispatch(RECOVERED_MESSAGE);
                    fallback.add(workload);
                    failed++;
                    continue;
                }
                switch (dispatchService.recoverAfterRestart(workload.getId(), RECOVERED_MESSAGE)) {
                    case KEPT -> kept++;
                    case CLOSED_AND_FAILED -> closed++;
                    case FAILED -> failed++;
                    default -> { }
                }
            }
            if (!fallback.isEmpty()) workloadRepository.saveAll(fallback);
            if (kept + closed + failed > 0) {
                log.warn("기동 회수: 중단된 실행에 배치된 워크로드 — 실행 계층이 받은 {}건은 그대로 두고, 전달 중이던 {}건과 현재 결정의 열린 전달 기록이 없는 {}건은 실패로 되돌렸습니다",
                        kept, closed, failed);
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

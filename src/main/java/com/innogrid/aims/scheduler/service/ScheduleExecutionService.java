package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
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

    /** 우선순위 높은 것부터, 같으면 먼저 등록된 것부터. */
    private static final Comparator<Workload> BY_PRIORITY_THEN_AGE =
            Comparator.comparingInt((Workload w) -> w.getPriority().ordinal()).reversed()
                    .thenComparing(Workload::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    private final ScheduleRepository scheduleRepository;
    private final ScheduleRunRepository runRepository;
    private final WorkloadRepository workloadRepository;
    private final SchedulingPolicyRepository policyRepository;
    private final SchedulingService schedulingService;
    private final PlacementService placementService;
    private final ScheduleService scheduleService;
    private final EngineRegistry engineRegistry;
    private final SchedulerProperties properties;

    public ScheduleExecutionService(ScheduleRepository scheduleRepository,
                                    ScheduleRunRepository runRepository,
                                    WorkloadRepository workloadRepository,
                                    SchedulingPolicyRepository policyRepository,
                                    SchedulingService schedulingService,
                                    PlacementService placementService,
                                    ScheduleService scheduleService,
                                    EngineRegistry engineRegistry,
                                    SchedulerProperties properties) {
        this.scheduleRepository = scheduleRepository;
        this.runRepository = runRepository;
        this.workloadRepository = workloadRepository;
        this.policyRepository = policyRepository;
        this.schedulingService = schedulingService;
        this.placementService = placementService;
        this.scheduleService = scheduleService;
        this.engineRegistry = engineRegistry;
        this.properties = properties;
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
                String message = emptyTargetMessage(schedule);
                if (triggerType == ScheduleRun.TriggerType.AUTO) {
                    // 5분 주기 스케줄 하나가 90일이면 "대상 없음" 행만 25,920개다.
                    // 이력은 남기지 않고 최근 실행 정보만 갱신해 화면에서 확인할 수 있게 한다.
                    complete(schedule, ScheduleRun.RunStatus.COMPLETED, startedAt, message);
                    return null;
                }
                run = startRun(schedule, triggerType, triggeredBy, engineId, policy);
                run.recordCounts(0, 0, 0, false);
                return finishRun(schedule, run, ScheduleRun.RunStatus.COMPLETED, startedAt, message);
            }

            run = startRun(schedule, triggerType, triggeredBy, engineId, policy);

            PlacementContext context = schedulingService.createContext(policy)
                    .withPowerBudget(schedule.getMaxPowerBudgetWatts());
            if (context.isEmpty()) {
                run.recordCounts(targets.size(), 0, 0, truncated);
                return finishRun(schedule, run, ScheduleRun.RunStatus.FAILED, startedAt,
                        SchedulingService.noActiveNodesReason(context));
            }

            int success = 0;
            String firstFailure = null;
            for (Workload workload : targets) {
                SchedulingResult result =
                        placementService.place(workload, context, engineId, run.getId());
                if (result.success()) {
                    success++;
                } else if (firstFailure == null) {
                    firstFailure = result.reason();
                }
            }

            int failed = targets.size() - success;
            run.recordCounts(targets.size(), success, failed, truncated);

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
    private List<Workload> findTargets(Schedule schedule) {
        Workload.ResourceType type = schedule.resolveResourceType();
        boolean matchNullType = type == Workload.ResourceType.VM;   // 기존 행은 VM 으로 해석한다

        return workloadRepository.findSchedulingCandidates(
                        TARGET_STATUSES, type, matchNullType, properties.getMaxRetryCount())
                .stream()
                .sorted(BY_PRIORITY_THEN_AGE)
                .toList();
    }

    /**
     * 대상이 없을 때의 메시지.
     * 재시도 상한에 걸려 빠진 건수를 함께 적는다 — 그러지 않으면 "할 일이 없어서"인지
     * "전부 소진되어서"인지 구분할 수 없다.
     */
    private String emptyTargetMessage(Schedule schedule) {
        Workload.ResourceType type = schedule.resolveResourceType();
        long exhausted = workloadRepository.countRetryExhausted(
                Workload.WorkloadStatus.FAILED, type,
                type == Workload.ResourceType.VM, properties.getMaxRetryCount());
        return exhausted == 0 ? "대상 없음"
                : String.format("대상 없음 (재시도 소진 %d건)", exhausted);
    }

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
     */
    private SchedulingPolicy applyOverrides(SchedulingPolicy policy, Schedule schedule) {
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

    /**
     * 최근 실행 정보를 갱신하고 다음 실행 시각을 다시 계산한다.
     *
     * <p>시작 시각을 인자로 받는다. 실행 권한을 잡는 조건부 UPDATE 가 영속성 컨텍스트를
     * 비우기 때문에 엔티티에 남아 있는 값은 갱신 전 상태다.</p>
     */
    private void complete(Schedule schedule, ScheduleRun.RunStatus status,
                          OffsetDateTime startedAt, String message) {
        schedule.recordRun(status, startedAt, OffsetDateTime.now(), message);
        scheduleService.refreshNextRunAt(schedule);
        scheduleRepository.save(schedule);
    }

    /**
     * 실패로 마무리한다. 여기서 다시 예외가 나면 원래 예외를 가리므로 삼킨다.
     * 상태를 못 풀면 스케줄이 RUNNING 으로 남아 영영 실행되지 않는다.
     */
    private void failQuietly(Schedule schedule, ScheduleRun run,
                             OffsetDateTime startedAt, String message) {
        try {
            if (run != null) {
                run.finish(ScheduleRun.RunStatus.FAILED, message);
                runRepository.save(run);
            }
            complete(schedule, ScheduleRun.RunStatus.FAILED, startedAt, message);
        } catch (RuntimeException e) {
            log.error("스케줄 실행 상태 정리 실패: schedule={}", schedule.getScheduleId(), e);
        }
    }
}

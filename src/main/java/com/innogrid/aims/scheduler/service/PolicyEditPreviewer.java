package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.innogrid.aims.scheduler.dto.PolicyDryRunResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PolicyChoice;
import com.innogrid.aims.scheduler.model.SchedulingResult;

/**
 * 스케줄링 서비스가 맡기는 <b>정책 수정 전 모의 배치</b> — 저장된 정책과 그 정책에 부분 수정을 얹은 사본으로 워크로드 초안 하나를
 * 각각 놓아 보고 나란히 돌려준다.
 *
 * <p>스프링 빈이 아니다. 아무것도 저장하지 않는다 — 고친 사본은 호출자가 저장 없이 만들어 넘기고, 평가기는 냉각 대기를 새로 걸지 않는
 * 사본을 쓴다. 메트릭 수집이 길어 트랜잭션을 열지 않는다.</p>
 *
 * <p>수집은 {@link PlacementContextAssembler#drainBase} 한 번이다. 기존 예약 · 노드 배치 중지 · MDC 전력 예산 · 링크를 같은 시각에
 * 읽고, 정책마다 노드 사본을 따로 떠서 같은 원장을 얹는다(노드 비우기 모의 배치와 같은 조립). 링크는 정책과 관계없이 읽지만
 * 링크 반영을 끈 정책은 {@link PlacementContext#linkAware}가 보지 않으므로 정책마다 따로 읽은 것과 결과가 같다. 두 결과가 같은
 * 측정값을 보므로 메트릭 흔들림이 비교에 끼지 않는다.</p>
 */
final class PolicyEditPreviewer {

    /** 응답에 싣는 한계 문장. */
    static final String LIMITATION = "저장하거나 예약하지 않는 지금 시점의 계산입니다. 같은 수집 한 번 위에서 저장된 정책과 고친 정책으로 "
            + "초안 하나를 따로 놓아 볼 뿐이라, 다른 대기 워크로드와의 경쟁, 호출 뒤의 노드 상태 변화, 스케줄이 지정한 온도 상한 · 전력 상한은 "
            + "보지 않습니다. 가중치 합이 다르면 두 결과의 finalScore 크기는 견주지 않습니다. 리소스 종류 · 활성 여부 · 이름 · 설명 수정은 "
            + "평가에 쓰지 않습니다";

    /** 한 건을 저장 없이 평가한다 — {@link SchedulingService#evaluateIn}과 같다. */
    @FunctionalInterface
    interface Evaluation {
        SchedulingResult evaluate(Workload workload, String engineId, PlacementContext context);
    }

    private final PlacementContextAssembler contextAssembler;

    /** 냉각 대기 기록을 가진 평가기. 사본을 떠서 기록은 읽기만 한다. */
    private final ConstraintEvaluator constraintEvaluator;

    /** 저장하지 않는 정책 선택({@link PolicyResolver#choose}) — 고쳐 보는 정책이 지금 배치에 쓰이는지 가린다. */
    private final Function<Workload.ResourceType, PolicyChoice> policies;

    private final Evaluation evaluation;
    private final EngineRegistry engineRegistry;

    PolicyEditPreviewer(PlacementContextAssembler contextAssembler,
                        ConstraintEvaluator constraintEvaluator,
                        Function<Workload.ResourceType, PolicyChoice> policies,
                        Evaluation evaluation,
                        EngineRegistry engineRegistry) {
        this.contextAssembler = contextAssembler;
        this.constraintEvaluator = constraintEvaluator;
        this.policies = policies;
        this.evaluation = evaluation;
        this.engineRegistry = engineRegistry;
    }

    /**
     * 정책 수정 전 모의 배치.
     *
     * @param policyId        고쳐 보는 정책 id
     * @param saved           저장된 정책(고치지 않는다)
     * @param edited          부분 수정을 얹은 저장되지 않은 사본
     * @param draft           저장하지 않은 워크로드 초안. 엔진은 워크로드를 바꾸지 않는다
     * @param requestedEngine 요청한 엔진(호출자가 등록 여부를 확인함). null 이면 쪽마다 그 정책의 기본 엔진
     */
    PolicyDryRunResponse preview(Long policyId, SchedulingPolicy saved, SchedulingPolicy edited, Workload draft,
                                 String requestedEngine) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Workload.ResourceType type = draft.resolveResourceType();
        SchedulingPolicy inUse = policies.apply(type).policy();
        boolean policyInUse = inUse != null && Objects.equals(inUse.getId(), policyId);
        String currentEngine = EngineChoice.choose(requestedEngine, saved, engineRegistry).engine();
        String editedEngine = EngineChoice.choose(requestedEngine, edited, engineRegistry).engine();

        PlacementContext collected = contextAssembler.drainBase(now).context();
        SchedulingResult current;
        SchedulingResult changed;
        if (collected.isEmpty()) {
            String reason = SchedulingService.noActiveNodesReason(collected);
            current = SchedulingResult.failure(reason, saved.getName());
            changed = SchedulingResult.failure(reason, edited.getName());
        } else {
            ConstraintEvaluator quiet = constraintEvaluator.copyWithoutCooldownMarking();
            current = evaluation.evaluate(draft, currentEngine, contextOf(collected, saved, quiet));
            changed = evaluation.evaluate(draft, editedEngine, contextOf(collected, edited, quiet));
        }
        boolean decisionChanged = current.success() != changed.success()
                || !Objects.equals(current.selectedNodeId(), changed.selectedNodeId());
        return new PolicyDryRunResponse(now, policyId, saved.getName(), policyInUse, type, changedFields(saved, edited),
                currentEngine, current, editedEngine, changed, decisionChanged, LIMITATION);
    }

    /** 정책 하나의 컨텍스트 — 수집 노드의 사본(예약 포함)에 같은 원장을 얹는다. 원본 노드에는 예약을 쌓지 않는다. */
    private static PlacementContext contextOf(PlacementContext collected, SchedulingPolicy policy,
                                              ConstraintEvaluator quiet) {
        List<NodeResource> nodes = collected.nodes().stream().map(NodeResource::snapshot).toList();
        return PlacementContext.of(nodes, policy, quiet)
                .withExcluded(collected.excludedNodes())
                .withLinkMatrix(collected.linkMatrix())
                .withMdcBudgets(collected.mdcBudgets())
                .withPlacementHolds(collected.placementHolds());
    }

    /** 저장된 값과 달라진 판단 필드 이름 — 고정 순서. 리소스 종류 · 활성 여부 · 이름 · 설명은 평가에 쓰지 않아 넣지 않는다. */
    static List<String> changedFields(SchedulingPolicy saved, SchedulingPolicy edited) {
        Map<String, Function<SchedulingPolicy, Object>> fields = new LinkedHashMap<>();
        fields.put("defaultEngine", SchedulingPolicy::getDefaultEngine);
        fields.put("warningTemperatureCelsius", SchedulingPolicy::getWarningTemperatureCelsius);
        fields.put("cpuWeight", SchedulingPolicy::getCpuWeight);
        fields.put("memoryWeight", SchedulingPolicy::getMemoryWeight);
        fields.put("powerWeight", SchedulingPolicy::getPowerWeight);
        fields.put("temperatureWeight", SchedulingPolicy::getTemperatureWeight);
        fields.put("networkWeight", SchedulingPolicy::getNetworkWeight);
        fields.put("maxCpuUsagePercent", SchedulingPolicy::getMaxCpuUsagePercent);
        fields.put("maxMemoryUsagePercent", SchedulingPolicy::getMaxMemoryUsagePercent);
        fields.put("maxTemperatureCelsius", SchedulingPolicy::getMaxTemperatureCelsius);
        fields.put("maxPowerUsagePercent", SchedulingPolicy::getMaxPowerUsagePercent);
        fields.put("powerAwareEnabled", SchedulingPolicy::isPowerAwareEnabled);
        fields.put("temperatureAwareEnabled", SchedulingPolicy::isTemperatureAwareEnabled);
        fields.put("affinityEnabled", SchedulingPolicy::isAffinityEnabled);
        List<String> changed = new ArrayList<>();
        fields.forEach((name, getter) -> {
            if (!Objects.equals(getter.apply(saved), getter.apply(edited))) changed.add(name);
        });
        return changed;
    }
}

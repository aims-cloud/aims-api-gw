package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.NodeDrainPreviewResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodePlacementHolds;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PolicyChoice;
import com.innogrid.aims.scheduler.model.ReservationJudgment;
import com.innogrid.aims.scheduler.model.SchedulingResult;

/**
 * 스케줄링 서비스가 맡기는 <b>노드 비우기 모의 배치</b> — 노드 한 대를 비운다고 할 때, 그 노드에 배정된 배치 대기 · 실행 중
 * 워크로드를 나머지 노드에 차례로 놓아 본다.
 *
 * <p>스프링 빈이 아니다. 아무것도 저장하지 않는다 — 정책은 저장 없이 고르고({@code default} 행을 만들지 않음), 평가기는 냉각
 * 대기를 새로 걸지 않는 사본을 쓴다. 메트릭 수집이 길어 트랜잭션을 열지 않는다.</p>
 *
 * <p>수집 1회와 예약 판정 1회를 같은 시각으로 묶은 {@link PlacementContextAssembler#drainBase}에서 시작한다. 옮길 곳은 비울
 * 노드를 뺀 수집 노드의 사본이고(예약 포함 · 통합 권고와 같은 방식), 워크로드 종류마다 정책 사본을 두되 모두 같은 노드 사본을
 * 쓴다 — 앞 건이 잡은 자리가 다른 종류의 뒤 건에도 보인다. 성공한 건만 그 자리에 예약으로 쌓는다(스케줄 모의 실행과 같음).</p>
 */
final class NodeDrainPreviewer {

    private static final Logger log = LoggerFactory.getLogger(NodeDrainPreviewer.class);

    /** 비울 노드 말고 수집된 노드가 없을 때의 사유. 통합 권고와 같은 문장이다. */
    static final String NO_OTHER_NODE = "옮길 다른 노드가 없습니다";

    /** 응답에 싣는 한계 문장. */
    static final String LIMITATION = "옮기거나 저장하지 않는 계산입니다. 배치 대기는 배치 예약 취소로 대기로 되돌리면 다음 배치가 다시 놓고, "
            + "실행 중은 실행 계층에서 끝내야 합니다. 다른 대기 워크로드와의 경쟁, 호출 뒤의 노드 상태 변화, 스케줄이 지정한 정책 · 온도 상한 · "
            + "전력 상한은 보지 않으며, 비운 노드의 전력은 MDC 전력 합계에서 뺍니다";

    /** 놓아 보는 순서 — 배치 대기(지금 예약 취소로 옮길 수 있는 것) 먼저, 실행 중 다음, 묶음 안은 스케줄 기본 순서, 같으면 id 순. */
    static final Comparator<PlacementContextAssembler.Held> ORDER =
            Comparator.comparingInt((PlacementContextAssembler.Held h) ->
                            h.workload().getStatus() == Workload.WorkloadStatus.RUNNING ? 1 : 0)
                    .thenComparing(PlacementContextAssembler.Held::workload,
                            ScheduleExecutionService.BY_PRIORITY_THEN_AGE)
                    .thenComparing(h -> h.workload().getId(), Comparator.nullsLast(Comparator.naturalOrder()));

    /** 한 건을 저장 없이 평가한다 — {@link SchedulingService#evaluateIn}과 같다. */
    @FunctionalInterface
    interface Evaluation {
        SchedulingResult evaluate(Workload workload, String engineId, PlacementContext context);
    }

    private final PlacementContextAssembler contextAssembler;

    /** 냉각 대기 기록을 가진 평가기. 사본을 떠서 기록은 읽기만 한다. */
    private final ConstraintEvaluator constraintEvaluator;

    /** 저장하지 않는 정책 선택({@link PolicyResolver#choose}). */
    private final Function<Workload.ResourceType, PolicyChoice> policies;

    private final Evaluation evaluation;
    private final EngineRegistry engineRegistry;
    private final SchedulerProperties properties;

    NodeDrainPreviewer(PlacementContextAssembler contextAssembler,
                       ConstraintEvaluator constraintEvaluator,
                       Function<Workload.ResourceType, PolicyChoice> policies,
                       Evaluation evaluation,
                       EngineRegistry engineRegistry,
                       SchedulerProperties properties) {
        this.contextAssembler = contextAssembler;
        this.constraintEvaluator = constraintEvaluator;
        this.policies = policies;
        this.evaluation = evaluation;
        this.engineRegistry = engineRegistry;
        this.properties = properties;
    }

    /**
     * 노드 비우기 모의 배치. 노드가 수집 후보 · 수집 제외 · 배치 대기/실행 중 워크로드의 배정 노드 · 배치 중지 원장 어디에도 없으면 404.
     *
     * @param nodeId          비울 노드. 앞뒤 공백은 빼고 대소문자를 구분해 정확히 맞춘다
     * @param requestedEngine 요청한 엔진(호출자가 등록 여부를 확인함). null이면 워크로드마다 정책 기본 엔진
     */
    NodeDrainPreviewResponse preview(String nodeId, String requestedEngine) {
        String target = nodeId == null ? "" : nodeId.trim();
        OffsetDateTime now = OffsetDateTime.now();
        PlacementContextAssembler.DrainBase base = contextAssembler.drainBase(now);
        PlacementContext collected = base.context();
        NodePlacementHolds holds = collected.placementHolds();

        boolean isCollected = collected.node(target).isPresent();
        boolean isExcluded = collected.excludedNodes().stream().anyMatch(e -> target.equals(e.nodeId()));
        boolean held = holds.isHeld(target);
        List<PlacementContextAssembler.Held> targets = base.reading().held().stream()
                .filter(h -> h.judgment() != ReservationJudgment.UNASSIGNED
                        && target.equals(h.workload().getAssignedNodeId()))
                .sorted(ORDER)
                .toList();
        if (!isCollected && !isExcluded && !held && targets.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "노드를 찾을 수 없습니다: " + target);
        }

        List<NodeResource> others = collected.nodes().stream()
                .filter(n -> !n.getNodeId().equals(target))
                .map(NodeResource::snapshot)
                .toList();
        boolean truncated = targets.size() > properties.getMaxWorkloadsPerRun();
        List<PlacementContextAssembler.Held> evaluated =
                truncated ? targets.subList(0, properties.getMaxWorkloadsPerRun()) : targets;

        ConstraintEvaluator quiet = constraintEvaluator.copyWithoutCooldownMarking();
        Map<Workload.ResourceType, PolicyChoice> choices = new EnumMap<>(Workload.ResourceType.class);
        Map<Workload.ResourceType, PlacementContext> contexts = new EnumMap<>(Workload.ResourceType.class);
        List<NodeDrainPreviewResponse.Decision> decisions = new ArrayList<>(evaluated.size());
        int placeable = 0;
        for (PlacementContextAssembler.Held h : evaluated) {
            Workload workload = h.workload();
            Workload.ResourceType type = workload.resolveResourceType();
            PolicyChoice choice = choices.computeIfAbsent(type, policies);
            PlacementContext context = contexts.computeIfAbsent(type, t -> PlacementContext
                    .of(others, choice.policy(), quiet)
                    .withExcluded(collected.excludedNodes())
                    .withLinkMatrix(collected.linkMatrix())
                    .withMdcBudgets(collected.mdcBudgets())
                    .withPlacementHolds(holds));
            String engine = EngineChoice.choose(requestedEngine, choice.policy(), engineRegistry).engine();
            String policyName = choice.policy() == null ? null : choice.policy().getName();

            SchedulingResult result;
            if (others.isEmpty()) {
                String reason = collected.isEmpty() ? SchedulingService.noActiveNodesReason(collected) : NO_OTHER_NODE;
                result = SchedulingResult.failure(reason, policyName);
            } else {
                try {
                    result = evaluation.evaluate(workload, engine, context);
                } catch (RuntimeException | LinkageError e) {
                    log.warn("노드 비우기 모의 배치 평가 실패(계속한다): node={}, workload={}", target, workload.getId(), e);
                    result = SchedulingResult.failure("평가 중 오류: " + e.getMessage(), policyName);
                }
            }
            if (result.success()) {
                context.reserve(result.selectedNodeId(), workload);
                placeable++;
            }
            decisions.add(new NodeDrainPreviewResponse.Decision(workload.getId(), workload.getName(),
                    workload.getStatus(), h.judgment(), type, result.policyName(), engine, result.success(),
                    result.selectedMdcId(), result.selectedNodeId(), result.finalScore(), result.reason(),
                    PlacementService.exclusionCodesOf(result)));
        }

        String holdReason = held && !holds.reasonOf(target).isEmpty() ? holds.reasonOf(target) : null;
        return new NodeDrainPreviewResponse(base.reading().now(), target, isCollected, held, holdReason, others.size(),
                targets.size(), placeable, truncated, !truncated && placeable == targets.size(), decisions, LIMITATION);
    }
}

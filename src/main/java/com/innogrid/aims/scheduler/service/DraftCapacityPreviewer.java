package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.DraftCapacityResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PolicyChoice;
import com.innogrid.aims.scheduler.model.SchedulingResult;

/**
 * 스케줄링 서비스가 맡기는 <b>워크로드 수용 개수 모의 배치</b> — 저장하지 않은 워크로드 초안 하나를 지금 노드에 몇 개까지 놓을 수
 * 있는지 센다.
 *
 * <p>스프링 빈이 아니다. 아무것도 저장하지 않는다 — 정책은 저장 없이 고르고({@code default} 행을 만들지 않음), 놓아 보는 곳은
 * 수집한 컨텍스트의 사본({@link PlacementContext#sandbox})이라 냉각 대기를 새로 걸지 않는다. 메트릭 수집이 길어 트랜잭션을 열지
 * 않는다.</p>
 *
 * <p>수집은 한 번이다. 기존 예약 · 노드 배치 중지 · MDC 전력 예산 · 링크는 단건 배치와 같은 {@link PlacementContextAssembler#createContext}
 * 결과를 그대로 쓰고, 같은 초안을 그 사본에 차례로 놓아 성공할 때마다 그 노드에 예약으로 쌓는다.</p>
 */
final class DraftCapacityPreviewer {

    private static final Logger log = LoggerFactory.getLogger(DraftCapacityPreviewer.class);

    /** 상한까지 모두 들어가 멈췄을 때의 사유. 인자는 상한. */
    static final String LIMIT_REACHED = "한 번에 세는 상한 %d개까지 모두 들어가 멈췄습니다";

    /** 응답에 싣는 한계 문장. */
    static final String LIMITATION = "저장하거나 예약하지 않는 지금 시점의 계산이며 예측이 아닙니다. 같은 요구량의 초안만 이어서 놓아 보므로 "
            + "다른 대기 워크로드와의 경쟁, 호출 뒤의 노드 상태 변화, 스케줄이 지정한 정책 · 온도 상한 · 전력 상한은 보지 않습니다. "
            + "CPU · 메모리 사용률 상한은 놓기 전 값으로 보므로 마지막으로 놓은 개수에서는 상한을 넘을 수 있고, "
            + "디스크 총량을 모르는 노드는 디스크로 막지 않습니다";

    private final PlacementContextAssembler contextAssembler;

    /** 저장하지 않는 정책 선택({@link PolicyResolver#choose}). */
    private final Function<Workload.ResourceType, PolicyChoice> policies;

    private final EngineRegistry engineRegistry;
    private final SchedulerProperties properties;

    DraftCapacityPreviewer(PlacementContextAssembler contextAssembler,
                           Function<Workload.ResourceType, PolicyChoice> policies,
                           EngineRegistry engineRegistry,
                           SchedulerProperties properties) {
        this.contextAssembler = contextAssembler;
        this.policies = policies;
        this.engineRegistry = engineRegistry;
        this.properties = properties;
    }

    /**
     * 워크로드 수용 개수 모의 배치.
     *
     * @param draft           저장하지 않은 초안({@code WorkloadService#draft}). 엔진은 워크로드를 바꾸지 않는다
     * @param requestedEngine 요청한 엔진(호출자가 등록 여부를 확인함). null이면 정책 기본 엔진
     */
    DraftCapacityResponse preview(Workload draft, String requestedEngine) {
        OffsetDateTime evaluatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        Workload.ResourceType type = draft.resolveResourceType();
        SchedulingPolicy policy = policies.apply(type).policy();
        String policyName = policy == null ? null : policy.getName();
        String engine = EngineChoice.choose(requestedEngine, policy, engineRegistry).engine();
        int limit = properties.getMaxWorkloadsPerRun();

        PlacementContext collected = contextAssembler.createContext(policy);
        int excludedCount = collected.excludedNodes().size();
        if (collected.isEmpty()) {
            return new DraftCapacityResponse(evaluatedAt, type, policyName, engine, 0, excludedCount, 0, limit, false,
                    SchedulingService.noActiveNodesReason(collected), null, List.of(), LIMITATION);
        }

        List<NodeResource> nodes = collected.nodes().stream().map(NodeResource::snapshot).toList();
        PlacementContext sandbox = collected.sandbox(nodes).withExcluded(collected.excludedNodes());
        SchedulingEngine scheduler = engineRegistry.resolve(engine);

        // 처음 들어가지 못하면 멈춘다. 들어가지 못한 시도는 사본에 예약을 남기지 않고 사본은 냉각 대기를 새로 걸지 않으므로
        // 다음 시도는 같은 사본에 같은 초안을 다시 놓는 것이고, 엔진은 적격 노드가 하나도 없을 때만 실패하므로 다시 놓아도
        // 들어가지 못한다(걸려 있던 냉각 대기가 호출 중에 풀리는 경우는 보지 않는다).
        Map<String, Integer> placed = new HashMap<>();
        SchedulingResult stop = null;
        int placeable = 0;
        while (placeable < limit) {
            SchedulingResult result = scheduler.schedule(draft, sandbox);
            if (placeable == 0) {
                // 시도마다 알리면 같은 경고가 최대 상한 줄만큼 남는다 — 처음 한 번만
                ConstraintEvaluator.warnUncheckedDisk(log, draft, result, sandbox, properties.getDiskMountPoint());
            }
            if (!result.success()) {
                stop = result;
                break;
            }
            sandbox.reserve(result.selectedNodeId(), draft);
            placed.merge(result.selectedNodeId(), 1, Integer::sum);
            placeable++;
        }

        boolean truncated = stop == null;
        Map<String, SchedulingResult.NodeScore> stopScores = new HashMap<>();
        if (stop != null) {
            stop.candidateScores().forEach(s -> stopScores.put(s.nodeId(), s));
        }
        List<DraftCapacityResponse.Node> rows = nodes.stream()
                .sorted(Comparator.comparing(NodeResource::getNodeId))
                .map(n -> {
                    SchedulingResult.NodeScore s = stopScores.get(n.getNodeId());
                    return new DraftCapacityResponse.Node(n.getMdcId(), n.getNodeId(),
                            placed.getOrDefault(n.getNodeId(), 0),
                            s == null ? null : s.exclusionReason(),
                            s == null ? null : s.exclusionCode());
                })
                .toList();
        String stopReason = truncated ? String.format(LIMIT_REACHED, limit) : stop.reason();
        return new DraftCapacityResponse(evaluatedAt, type, policyName, engine, nodes.size(), excludedCount, placeable,
                limit, truncated, stopReason, PlacementService.exclusionCodesOf(stop), rows, LIMITATION);
    }
}

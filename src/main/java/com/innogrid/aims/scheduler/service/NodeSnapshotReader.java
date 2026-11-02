package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.ThermalCooldownResponse;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;

/**
 * 스케줄링 서비스에서 떼어 낸 <b>노드 조회</b> — 열 상태를 붙인 노드 스냅샷, 노드 한 대 찾기(수집 제외 사유를 담은 404),
 * 냉각 대기 노드 목록.
 *
 * <p>스프링 빈이 아니다. 서비스가 생성자에서 한 번 만들고, 생성자는 참조만 저장한다. 어느 정책으로 볼지는 서비스가 고른다.</p>
 */
final class NodeSnapshotReader {

    private final PlacementContextAssembler contextAssembler;

    /** 냉각 대기 기록을 가진 평가기. 컨텍스트 조립과 같은 인스턴스여야 대기 판정이 갈라지지 않는다. */
    private final ConstraintEvaluator constraintEvaluator;

    NodeSnapshotReader(PlacementContextAssembler contextAssembler, ConstraintEvaluator constraintEvaluator) {
        this.contextAssembler = contextAssembler;
        this.constraintEvaluator = constraintEvaluator;
    }

    /** 정책으로 배치 컨텍스트를 만들고 후보 노드마다 열 상태를 붙인다. */
    PlacementContext snapshot(SchedulingPolicy policy) {
        PlacementContext context = contextAssembler.createContext(policy);
        for (NodeResource node : context.nodes()) {
            node.setThermalState(constraintEvaluator.thermalState(node, policy));
        }
        return context;
    }

    /** 스냅샷에서 노드 한 대를 찾는다. 없으면 404 — 수집 단계에서 제외된 노드라면 사유에 그 이유를 적는다. */
    NodeResource find(PlacementContext snapshot, String nodeId) {
        return snapshot.nodes().stream()
                .filter(n -> n.getNodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        snapshot.excludedNodes().stream()
                                .filter(e -> e.nodeId().equals(nodeId))
                                .findFirst()
                                .map(e -> "노드를 찾을 수 없습니다: " + nodeId + " — 수집 제외 (" + e.reason() + ")")
                                .orElse("노드를 찾을 수 없습니다: " + nodeId)));
    }

    /**
     * 냉각 대기 중인 노드 목록. 대기 노드가 하나도 없으면 노드 목록을 받지 않는다 — 정책 조회와 노드 수집이 모두 그 안에 있다.
     * 받은 뒤에는 노드마다 다시 확인하므로, 수집하는 동안 만료된 노드는 목록에서 빠진다.
     */
    List<ThermalCooldownResponse> coolingDown(Supplier<List<NodeResource>> nodes) {
        if (!constraintEvaluator.anyCoolingDown()) return List.of();
        return nodes.get().stream()
                .filter(n -> constraintEvaluator.isCoolingDown(n.getNodeId()))
                .map(n -> ThermalCooldownResponse.of(n, constraintEvaluator.remainingCooldownMinutes(n.getNodeId())))
                .toList();
    }
}

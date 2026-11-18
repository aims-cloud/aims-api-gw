package com.innogrid.aims.scheduler.model;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.innogrid.aims.scheduler.entity.NodePowerSpec;

/**
 * 전력 사양 원장의 스냅샷 — 수집 <b>1회당 한 번만</b> 읽어 노드마다 DB를 치지 않게 한다.
 * MDC 링크의 {@link LinkMatrix}와 같은 방식이다.
 *
 * <p>원장에 없는 노드는 비어 있는 값을 돌려주고, 호출자가 기존 경로(설정 override → 기본값)로 넘어간다.
 * 그래서 원장이 비어 있으면 모든 노드가 기존 경로를 그대로 탄다.</p>
 */
public record NodePowerSpecs(Map<String, Double> capacityByNode) {

    public static final NodePowerSpecs EMPTY = new NodePowerSpecs(Map.of());

    public NodePowerSpecs {
        capacityByNode = capacityByNode == null ? Map.of() : Map.copyOf(capacityByNode);
    }

    /** 같은 노드가 둘 이상이면(유니크 제약이 막지만) 뒤엣것을 쓴다. */
    public static NodePowerSpecs of(List<NodePowerSpec> specs) {
        if (specs == null || specs.isEmpty()) return EMPTY;
        return new NodePowerSpecs(specs.stream().collect(Collectors.toMap(
                NodePowerSpec::getNodeId, NodePowerSpec::getCapacityWatts, (a, b) -> b)));
    }

    /** 원장에 적힌 정격(W). 없으면 비어 있다. */
    public OptionalDouble capacityOf(String nodeId) {
        Double v = capacityByNode.get(nodeId);
        return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
    }

    /** 원장 우선, 없으면 {@code fallback}이 계산한 값. 수집 지점이 쓰는 형태다. */
    public double capacityOr(String nodeId, Function<String, Double> fallback) {
        Double v = capacityByNode.get(nodeId);
        return v != null ? v : fallback.apply(nodeId);
    }

    public boolean isEmpty() { return capacityByNode.isEmpty(); }
}

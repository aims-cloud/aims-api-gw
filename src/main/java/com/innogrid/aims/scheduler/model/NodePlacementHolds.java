package com.innogrid.aims.scheduler.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.innogrid.aims.scheduler.entity.NodePlacementHold;

/**
 * 노드 배치 중지 원장의 스냅샷 — 배치 1회당 한 번만 읽어 후보마다 DB를 치지 않게 한다. MDC 전력 예산의
 * {@link MdcPowerBudgets}와 같은 방식이다.
 *
 * <p>값은 노드 id → 사유(없으면 빈 문자열)이고 노드 id 순으로 둔다. 비어 있으면 제약이 아무 노드도 막지 않아 배치 동작이
 * 이 기능 이전과 같다.</p>
 */
public record NodePlacementHolds(Map<String, String> reasonByNode) {

    public static final NodePlacementHolds EMPTY = new NodePlacementHolds(Map.of());

    public NodePlacementHolds {
        reasonByNode = reasonByNode == null || reasonByNode.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(reasonByNode));
    }

    /** 원장 행으로 만든다. 같은 노드가 둘 이상이면(유니크 제약이 막지만) 뒤엣것을 쓴다. */
    public static NodePlacementHolds of(List<NodePlacementHold> holds) {
        if (holds == null || holds.isEmpty()) return EMPTY;
        Map<String, String> byNode = new TreeMap<>();
        for (NodePlacementHold h : holds) {
            byNode.put(h.getNodeId(), h.getReason() == null ? "" : h.getReason());
        }
        return new NodePlacementHolds(byNode);
    }

    /** 이 노드의 배치를 중지했는지. 노드 id 는 대소문자를 구분해 정확히 맞춘다. */
    public boolean isHeld(String nodeId) {
        return nodeId != null && reasonByNode.containsKey(nodeId);
    }

    /** 이 노드의 중지 사유. 중지하지 않았거나 사유가 없으면 빈 문자열이다. */
    public String reasonOf(String nodeId) {
        return nodeId == null ? "" : reasonByNode.getOrDefault(nodeId, "");
    }

    public int size() { return reasonByNode.size(); }
    public boolean isEmpty() { return reasonByNode.isEmpty(); }
}

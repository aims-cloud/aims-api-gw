package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 전력 최적화 진단이 수집 단계에서 제외된 노드를 응답에 싣는지.
 *
 * <p>제외 노드는 판단에 쓰지 않는다 — 판단할 값이 없어서 뺀 노드다. 하지만 응답에 없으면
 * "10대 중 1대 제외"가 "9대 클러스터"로 보인다.</p>
 */
class PowerOptimizationExcludedTest {

    private final SchedulerProperties properties = new SchedulerProperties();
    private final SchedulingService schedulingService = mock(SchedulingService.class);
    private final PowerOptimizationService service = new PowerOptimizationService(
            schedulingService, new PowerModel(properties), properties);

    @DisplayName("제외 노드는 판단에서 빼고 응답에는 수와 사유를 싣는다")
    @Test
    void excluded_nodes_counted_with_reason() {
        List<NodeResource> nodes = List.of(node("n1", 50, 250), node("n2", 2, 150));
        List<ExcludedNode> excluded = List.of(
                new ExcludedNode("mdc-01", "n3", List.of("cpu사용률")),
                new ExcludedNode("mdc-01", "n4", List.of("코어수", "MemTotal")));

        PowerOptimizationResponse r = service.diagnose(nodes, excluded);

        assertEquals(2, r.cluster().totalNodes(), "totalNodes 는 판단 가능한 노드만 센다");
        assertEquals(2, r.cluster().excludedNodes());
        assertEquals("n3", r.cluster().excluded().get(0).nodeId());
        assertTrue(r.cluster().excluded().get(1).reason().contains("코어수, MemTotal"));
        // 제외 노드는 진단 항목에 나오지 않는다
        assertTrue(r.findings().stream().noneMatch(f -> f.nodeId().equals("n3") || f.nodeId().equals("n4")));
    }

    @DisplayName("제외 노드가 없으면 0과 빈 배열이다")
    @Test
    void no_excluded_node_gives_0_and_empty() {
        PowerOptimizationResponse r = service.diagnose(List.of(node("n1", 50, 250)));

        assertEquals(0, r.cluster().excludedNodes());
        assertTrue(r.cluster().excluded().isEmpty());
    }

    @DisplayName("현재 상태 진단은 스냅샷의 제외 목록을 그대로 쓴다")
    @Test
    void current_diagnosis_uses_snapshot_excluded() {
        PlacementContext snapshot = PlacementContext.of(
                List.of(node("n1", 50, 250)), new com.innogrid.aims.scheduler.entity.SchedulingPolicy("t", "t"))
                .withExcluded(List.of(new ExcludedNode("mdc-01", "n9", List.of("MemAvailable"))));
        when(schedulingService.nodeSnapshot()).thenReturn(snapshot);

        PowerOptimizationResponse r = service.diagnoseCurrent();

        assertEquals(1, r.cluster().totalNodes());
        assertEquals(1, r.cluster().excludedNodes());
        assertEquals("n9", r.cluster().excluded().get(0).nodeId());
    }

    private static NodeResource node(String id, double cpu, double power) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, cpu, 40, 30, power, 500, 45, 1000, 500);
    }
}

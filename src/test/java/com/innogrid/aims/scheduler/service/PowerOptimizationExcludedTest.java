package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;

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

    @Test
    void 제외_노드는_판단에서_빼고_응답에는_수와_사유를_싣는다() {
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

    @Test
    void 제외_노드가_없으면_0과_빈_배열이다() {
        PowerOptimizationResponse r = service.diagnose(List.of(node("n1", 50, 250)));

        assertEquals(0, r.cluster().excludedNodes());
        assertTrue(r.cluster().excluded().isEmpty());
    }

    @Test
    void 현재_상태_진단은_스냅샷의_제외_목록을_그대로_쓴다() {
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

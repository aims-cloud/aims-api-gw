package com.innogrid.aims.scheduler.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import static org.junit.jupiter.api.Assertions.*;

class WeightedScoreSchedulerTest {

    private final WeightedScoreScheduler scheduler = new WeightedScoreScheduler();

    @Test
    void 자원_여유가_많은_노드를_선택한다() {
        Workload workload = new Workload("test-job", 2, 4096);
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");

        List<NodeResource> candidates = List.of(
                createNode("mdc-01", "node-1", 8, 16384, 70, 80, 300, 500, 65),
                createNode("mdc-01", "node-2", 8, 16384, 30, 40, 200, 500, 55)
        );

        SchedulingResult result = scheduler.schedule(workload, candidates, policy);

        assertTrue(result.success());
        assertEquals("node-2", result.selectedNodeId());
        assertTrue(result.finalScore() > 0);
    }

    @Test
    void 자원_부족한_노드는_제외한다() {
        Workload workload = new Workload("big-job", 16, 32768);
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");

        List<NodeResource> candidates = List.of(
                createNode("mdc-01", "node-1", 8, 16384, 50, 50, 250, 500, 60)
        );

        SchedulingResult result = scheduler.schedule(workload, candidates, policy);

        assertFalse(result.success());
        assertFalse(result.candidateScores().isEmpty());
        assertFalse(result.candidateScores().get(0).eligible());
    }

    @Test
    void 선호_MDC에_보너스를_준다() {
        Workload workload = new Workload("preferred-job", 2, 4096);
        workload.setPreferredMdcId("mdc-02");
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");

        // 두 노드의 자원 상태가 동일하지만 MDC가 다름
        List<NodeResource> candidates = List.of(
                createNode("mdc-01", "node-1", 8, 16384, 40, 40, 200, 500, 55),
                createNode("mdc-02", "node-2", 8, 16384, 40, 40, 200, 500, 55)
        );

        SchedulingResult result = scheduler.schedule(workload, candidates, policy);

        assertTrue(result.success());
        assertEquals("node-2", result.selectedNodeId()); // 선호 MDC 선택
    }

    @Test
    void 온도_임계치_초과_노드는_제외한다() {
        Workload workload = new Workload("temp-job", 2, 4096);
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");
        policy.setMaxTemperatureCelsius(70);

        List<NodeResource> candidates = List.of(
                createNode("mdc-01", "node-hot", 8, 16384, 30, 30, 200, 500, 80),
                createNode("mdc-01", "node-cool", 8, 16384, 30, 30, 200, 500, 50)
        );

        SchedulingResult result = scheduler.schedule(workload, candidates, policy);

        assertTrue(result.success());
        assertEquals("node-cool", result.selectedNodeId());
    }

    @Test
    void 후보_노드가_없으면_실패한다() {
        Workload workload = new Workload("empty-job", 2, 4096);
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");

        SchedulingResult result = scheduler.schedule(workload, List.of(), policy);

        assertFalse(result.success());
    }

    private NodeResource createNode(String mdcId, String nodeId, int cpu, long memMb,
                                     double cpuUsage, double memUsage,
                                     double power, double powerCap, double temp) {
        return new NodeResource(mdcId, nodeId, cpu, memMb, 0,
                cpuUsage, memUsage, 40, power, powerCap, temp, 1000, 500);
    }
}

package com.innogrid.aims.scheduler.engine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import static org.junit.jupiter.api.Assertions.*;

class PowerAwareSchedulerTest {

    private final PowerAwareScheduler scheduler = new PowerAwareScheduler();

    @DisplayName("전력 효율이 높은 노드를 선호한다")
    @Test
    void power_efficient_node_preferred() {
        Workload workload = new Workload("power-job", 2, 4096);
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");

        List<NodeResource> candidates = List.of(
                // 높은 CPU 사용률이지만 전력 소비가 낮음 → 효율적
                createNode("mdc-01", "efficient", 8, 16384, 60, 50, 200, 500, 55),
                // 낮은 CPU 사용률인데 전력 소비가 높음 → 비효율적
                createNode("mdc-01", "wasteful", 8, 16384, 30, 50, 400, 500, 55)
        );

        SchedulingResult result = scheduler.schedule(workload, candidates, policy);

        assertTrue(result.success());
        assertEquals("efficient", result.selectedNodeId());
    }

    @DisplayName("이미 가동중인 서버를 선호한다 전력통합")
    @Test
    void active_server_preferred() {
        Workload workload = new Workload("consolidate-job", 2, 4096);
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");

        List<NodeResource> candidates = List.of(
                // 이미 가동 중 (CPU 50%) → 전력 통합 보너스
                createNode("mdc-01", "active", 8, 16384, 50, 40, 250, 500, 55),
                // 거의 유휴 (CPU 5%)인데 전력 소비가 높음 → 비효율적 서버
                createNode("mdc-01", "idle", 8, 16384, 5, 10, 350, 500, 45)
        );

        SchedulingResult result = scheduler.schedule(workload, candidates, policy);

        assertTrue(result.success());
        assertEquals("active", result.selectedNodeId());
    }

    @DisplayName("고온 서버에 냉각 비용 패널티를 부여한다")
    @Test
    void hot_server_cooling_penalty() {
        Workload workload = new Workload("thermal-job", 2, 4096);
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");
        policy.setMaxTemperatureCelsius(80);

        List<NodeResource> candidates = List.of(
                createNode("mdc-01", "hot", 8, 16384, 40, 40, 200, 500, 75),
                createNode("mdc-01", "cool", 8, 16384, 40, 40, 200, 500, 40)
        );

        SchedulingResult result = scheduler.schedule(workload, candidates, policy);

        assertTrue(result.success());
        assertEquals("cool", result.selectedNodeId());
    }

    private NodeResource createNode(String mdcId, String nodeId, int cpu, long memMb,
                                     double cpuUsage, double memUsage,
                                     double power, double powerCap, double temp) {
        return new NodeResource(mdcId, nodeId, cpu, memMb, 0,
                cpuUsage, memUsage, 40, power, powerCap, temp, 1000, 500);
    }
}

package com.innogrid.aims.scheduler.engine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import static org.junit.jupiter.api.Assertions.*;

class TemperatureAwareSchedulerTest {

    private final TemperatureAwareScheduler scheduler = new TemperatureAwareScheduler();

    @DisplayName("온도가 낮은 노드를 선택한다")
    @Test
    void cooler_node_selected() {
        Workload workload = new Workload("temp-job", 2, 4096);
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");
        policy.setMaxTemperatureCelsius(80);

        List<NodeResource> candidates = List.of(
                createNode("mdc-01", "hot", 8, 16384, 30, 30, 200, 500, 70),
                createNode("mdc-01", "cool", 8, 16384, 30, 30, 200, 500, 40),
                createNode("mdc-01", "warm", 8, 16384, 30, 30, 200, 500, 55)
        );

        SchedulingResult result = scheduler.schedule(workload, candidates, policy);

        assertTrue(result.success());
        assertEquals("cool", result.selectedNodeId());
    }

    @DisplayName("열적 균형을 유지한다 평균 이하 노드 선호")
    @Test
    void below_average_node_preferred() {
        Workload workload = new Workload("balance-job", 2, 4096);
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");
        policy.setMaxTemperatureCelsius(80);

        // 평균 온도 = (60 + 50 + 70) / 3 = 60°C
        // node-2(50°C)가 평균 이하 → 열적 균형 보너스
        List<NodeResource> candidates = List.of(
                createNode("mdc-01", "node-1", 8, 16384, 30, 30, 200, 500, 60),
                createNode("mdc-01", "node-2", 8, 16384, 30, 30, 200, 500, 50),
                createNode("mdc-01", "node-3", 8, 16384, 30, 30, 200, 500, 70)
        );

        SchedulingResult result = scheduler.schedule(workload, candidates, policy);

        assertTrue(result.success());
        assertEquals("node-2", result.selectedNodeId());
    }

    @DisplayName("열관성 예측으로 배치후 과열 노드를 제외한다")
    @Test
    void predicted_overheat_excluded() {
        // 큰 워크로드(CPU 6코어) + 이미 높은 온도 → 배치 후 온도 초과 예측
        Workload workload = new Workload("heavy-job", 6, 8192);
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");
        policy.setMaxTemperatureCelsius(75);

        List<NodeResource> candidates = List.of(
                // 현재 72°C + 열관성 예측 → 초과
                createNode("mdc-01", "almost-hot", 8, 16384, 20, 20, 200, 500, 72),
                // 현재 50°C → 여유 있음
                createNode("mdc-01", "safe", 8, 16384, 20, 20, 200, 500, 50)
        );

        SchedulingResult result = scheduler.schedule(workload, candidates, policy);

        assertTrue(result.success());
        assertEquals("safe", result.selectedNodeId());
    }

    @DisplayName("냉각 비용 제곱 모델이 고온 노드에 패널티를 준다")
    @Test
    void squared_cooling_penalizes_hot() {
        Workload workload = new Workload("cooling-job", 2, 4096);
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");
        policy.setMaxTemperatureCelsius(80);

        // 두 노드 모두 자원은 동일, 온도만 다름
        // 냉각 비용: (65/80)^2 = 0.66 vs (35/80)^2 = 0.19
        List<NodeResource> candidates = List.of(
                createNode("mdc-01", "high-temp", 8, 16384, 30, 30, 200, 500, 65),
                createNode("mdc-01", "low-temp", 8, 16384, 30, 30, 200, 500, 35)
        );

        SchedulingResult result = scheduler.schedule(workload, candidates, policy);

        assertTrue(result.success());
        assertEquals("low-temp", result.selectedNodeId());

        // 점수 차이가 있어야 함 (냉각 비용 차이)
        var scores = result.candidateScores();
        assertTrue(scores.get(0).totalScore() > scores.get(1).totalScore());
    }

    private NodeResource createNode(String mdcId, String nodeId, int cpu, long memMb,
                                     double cpuUsage, double memUsage,
                                     double power, double powerCap, double temp) {
        return new NodeResource(mdcId, nodeId, cpu, memMb, 0,
                cpuUsage, memUsage, 40, power, powerCap, temp, 1000, 500);
    }
}

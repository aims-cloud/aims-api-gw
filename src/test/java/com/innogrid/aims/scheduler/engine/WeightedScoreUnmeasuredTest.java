package com.innogrid.aims.scheduler.engine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * weighted 엔진이 전력·온도를 수집하지 못한 노드를 어떻게 다루는지.
 *
 * <p>0W·0°C를 그대로 계산하면 "여유 100%"로 읽혀 만점이 된다. IPMI가 없는 노드가 계측된
 * 노드보다 유리해지는 것은 근거 없는 편향이다 — {@code power} 엔진처럼 중립 0.5를 준다.</p>
 */
class WeightedScoreUnmeasuredTest {

    private static final double EPS = 1e-9;

    private final WeightedScoreScheduler scheduler = new WeightedScoreScheduler();

    @DisplayName("전력을 수집하지 못한 노드의 전력 점수는 중립 0_5다")
    @Test
    void unmeasured_power_score_0_5() {
        NodeScore score = only(scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("n", 0, 500, 55)), policy()));

        assertEquals(0.5, score.powerScore(), EPS, "0W 를 여유 100% 로 읽어 1.0 을 주면 안 된다");
    }

    @DisplayName("온도를 수집하지 못한 노드의 온도 점수는 중립 0_5다")
    @Test
    void unmeasured_temp_score_0_5() {
        NodeScore score = only(scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("n", 200, 500, 0)), policy()));

        assertEquals(0.5, score.temperatureScore(), EPS, "0°C 를 여유 100% 로 읽어 1.0 을 주면 안 된다");
    }

    @DisplayName("계측된 노드의 점수는 그대로다")
    @Test
    void measured_node_score_unchanged() {
        NodeScore score = only(scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("n", 200, 500, 55)), policy()));

        assertEquals(0.60, score.powerScore(), EPS);
        assertEquals(0.2667, score.temperatureScore(), EPS);   // (75-55)/75
    }

    @DisplayName("IPMI가 섞인 클러스터에서 미계측 노드가 유리하지 않다")
    @Test
    void unmeasured_not_favored_mixed_ipmi() {
        // 자원·네트워크 동일. measured는 전력 여유 60%·온도 여유 46.7%로 양호한 노드다.
        NodeResource measured = node("measured", 200, 500, 40);
        NodeResource unmeasured = node("unmeasured", 0, 500, 0);

        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512),
                List.of(unmeasured, measured), policy());

        assertEquals("measured", result.selectedNodeId(),
                "미계측 노드가 만점(1.0·1.0)을 받으면 계측된 양호 노드를 이긴다");
    }

    @DisplayName("전력 인지를 끈 정책에서는 계측 여부와 무관하게 0_5다")
    @Test
    void power_aware_off_always_0_5() {
        SchedulingPolicy policy = policy();
        policy.setPowerAwareEnabled(false);

        NodeScore score = only(scheduler.schedule(new Workload("w", 1, 512),
                List.of(node("n", 200, 500, 55)), policy));

        assertEquals(0.5, score.powerScore(), EPS);
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private static SchedulingPolicy policy() {
        return new SchedulingPolicy("test", "test");
    }

    private static NodeScore only(SchedulingResult result) {
        assertEquals(1, result.candidateScores().size());
        return result.candidateScores().get(0);
    }

    private static NodeResource node(String id, double power, double capacity, double temp) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, 30, 40, 40, power, capacity, temp, 1000, 500);
    }
}

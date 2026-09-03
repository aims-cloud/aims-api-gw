package com.innogrid.aims.scheduler.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * temperature 엔진의 점수가 예약분(실효 온도·실효 자원)을 반영하는지,
 * 열적 균형의 기준인 클러스터 평균도 같은 기준(실효)인지.
 */
class TemperatureAwareEffectiveTest {

    private static final double EPS = 1e-9;
    private static final double MAX_TEMP = 80.0;

    private final TemperatureAwareScheduler scheduler = new TemperatureAwareScheduler();
    private final double inertiaK = new SchedulerProperties().getThermal().getInertiaK();

    @DisplayName("예약이 없으면 점수는 실측 계산값과 같다")
    @Test
    void no_reservation_matches_measured() {
        List<NodeResource> nodes = List.of(node("A", 50), node("B", 60), node("C", 70));

        SchedulingResult result = scheduler.schedule(new Workload("w", 2, 1024), nodes, policy());

        NodeScore a = score(result, "A");
        assertEquals(0.375, a.details().get("열적여유"), EPS);          // (80-50)/80
        assertEquals(60.0, a.details().get("클러스터평균온도C"), EPS);    // 실효=실측 → (50+60+70)/3
        // 균형 = 0.5 + 0.5×(60-50)/60
        assertEquals(round4(0.5 + 0.5 * 10 / 60), a.details().get("열적균형"), EPS);
        assertEquals("A", result.selectedNodeId());
    }

    @DisplayName("예약이 쌓인 노드는 실효 온도로 평가되고 클러스터 평균도 같은 기준이다")
    @Test
    void reserved_node_scored_by_effective_temp() {
        NodeResource a = node("A", 50);
        NodeResource b = node("B", 50);
        PlacementContext context = PlacementContext.of(List.of(a, b), policy());

        context.reserve("A", new Workload("reserved", 4, 4096));     // 4/8 코어 = 50%

        SchedulingResult result = scheduler.schedule(new Workload("w", 1, 512), context);

        double effA = 50 * (1 + inertiaK * 0.5);                        // 53.75
        double avg = (effA + 50) / 2;                                   // 51.875 — 평균도 실효
        NodeScore sa = score(result, "A");
        NodeScore sb = score(result, "B");

        assertEquals(round4((MAX_TEMP - effA) / MAX_TEMP), sa.details().get("열적여유"), EPS);
        assertEquals(round4(avg), sa.details().get("클러스터평균온도C"), EPS,
                "평균을 실측(50)으로 두면 B 가 '평균 수준(0.5)'로 보여 기준점이 어긋난다");
        assertEquals(round4(0.5 + 0.5 * (avg - effA) / avg), sa.details().get("열적균형"), EPS);
        assertEquals(round4(0.5 + 0.5 * (avg - 50) / avg), sb.details().get("열적균형"), EPS);
        assertTrue(sb.details().get("열적균형") > 0.5, "예약을 안 받은 B 는 평균보다 시원해야 한다");
        assertEquals("B", result.selectedNodeId());
    }

    @DisplayName("자원 점수도 예약분을 반영한다")
    @Test
    void resource_score_reflects_reservation() {
        NodeResource a = node("A", 50);
        PlacementContext context = PlacementContext.of(List.of(a), policy());
        context.reserve("A", new Workload("reserved", 2, 4096));     // cpu +25%p, mem +25%p

        NodeScore sa = score(scheduler.schedule(new Workload("w", 1, 512), context), "A");

        // 실측 cpu 30·mem 30 → 실효 55·55 → (0.45×0.5)+(0.45×0.5)
        assertEquals(0.45, sa.details().get("자원여유"), EPS);
    }

    @DisplayName("다건 연속 배치가 한 노드로 몰리지 않는다")
    @Test
    void placements_do_not_pile_up() {
        List<NodeResource> nodes = List.of(node("A", 50), node("B", 50), node("C", 50));
        PlacementContext context = PlacementContext.of(nodes, policy());

        Map<String, Integer> placed = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            Workload w = new Workload("w" + i, 2, 1024);
            SchedulingResult result = scheduler.schedule(w, context);
            assertTrue(result.success());
            context.reserve(result.selectedNodeId(), w);
            placed.merge(result.selectedNodeId(), 1, Integer::sum);
        }

        assertEquals(Map.of("A", 2, "B", 2, "C", 2), placed);
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private static SchedulingPolicy policy() {
        SchedulingPolicy p = new SchedulingPolicy("test", "test");
        p.setMaxTemperatureCelsius(MAX_TEMP);
        return p;
    }

    private static NodeScore score(SchedulingResult result, String nodeId) {
        return result.candidateScores().stream()
                .filter(s -> s.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static NodeResource node(String id, double temp) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, 30, 30, 40, 200, 500, temp, 1000, 500);
    }
}

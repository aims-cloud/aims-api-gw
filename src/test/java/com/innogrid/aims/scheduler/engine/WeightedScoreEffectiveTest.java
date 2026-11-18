package com.innogrid.aims.scheduler.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * weighted 엔진의 점수가 예약분(실효값)을 반영하는지.
 *
 * <p>배포 VM 실증(2026-09-02) 수치를 쓴다. 2코어 노드에 1코어를 예약하면 같은 요청의 제약 검사는 "가용 CPU 0.7"로
 * 예약을 본다. 점수가 예약을 반영하지 않으면 dryRun의 cpu여유가 0.8733 그대로라 제약과 점수가 다른 세상을 본다.
 * 이 클래스는 그 실증 수치를 그대로 재현해 확인한다.</p>
 */
class WeightedScoreEffectiveTest {

    private static final double EPS = 1e-9;

    private final WeightedScoreScheduler scheduler = new WeightedScoreScheduler();
    private final double inertiaK = new SchedulerProperties().getThermal().getInertiaK();

    @DisplayName("예약이 없으면 점수는 실측 계산값과 같다")
    @Test
    void no_reservation_matches_measured() {
        // 실효=실측이므로 단건 배치의 점수는 실측 계산값과 같아야 한다 (불변 조건)
        NodeResource node = node("n1", 8, 16384, 30, 40, 200, 500, 55);
        SchedulingPolicy policy = policy();   // maxTemperature 75

        NodeScore score = only(scheduler.schedule(new Workload("w", 2, 1024), List.of(node), policy));

        assertEquals(0.70, score.cpuScore(), EPS);          // (100-30)/100
        assertEquals(0.60, score.memoryScore(), EPS);       // (100-40)/100
        assertEquals(0.60, score.powerScore(), EPS);        // 1 - 200/500
        assertEquals(0.2667, score.temperatureScore(), EPS); // (75-55)/75
    }

    @DisplayName("VM 실증 재현 2코어 노드에 1코어 예약하면 cpu여유가 0_8733에서 0_3733로 내려간다")
    @Test
    void reserve_lowers_cpu_headroom_0_3733() {
        // 배포 VM 그대로: 2코어, 실측 CPU 12.67%, IPMI 없음(전력 0W·온도 0°C)
        NodeResource node = node("localhost:9100", 2, 3900, 12.67, 68.73, 0, 500, 0);
        SchedulingPolicy policy = policy();
        Workload a = new Workload("defect1-a", 1, 0);
        Workload b = new Workload("defect1-b", 1, 0);

        PlacementContext context = PlacementContext.of(List.of(node), policy);

        NodeScore before = only(scheduler.schedule(a, context));
        assertEquals(0.8733, before.cpuScore(), EPS);

        context.reserve("localhost:9100", a);               // A 배치 확정 → 예약 1코어(50%p)

        NodeScore after = only(scheduler.schedule(b, context));
        assertEquals(0.3733, after.cpuScore(), EPS,
                "예약 1코어(50%p)가 점수에 반영되어야 한다 — 실측만 보면 0.8733 그대로다");
    }

    @DisplayName("메모리 전력 온도 점수도 예약분을 반영한다")
    @Test
    void mem_power_temp_reflect_reservation() {
        NodeResource node = node("n1", 8, 16384, 30, 40, 200, 500, 60);
        SchedulingPolicy policy = policy();
        PlacementContext context = PlacementContext.of(List.of(node), policy);

        context.reserve("n1", new Workload("reserved", 2, 4096));   // 2/8 코어 = 25%, 4096/16384 = 25%

        NodeScore score = only(scheduler.schedule(new Workload("w", 1, 512), context));

        assertEquals(0.45, score.cpuScore(), EPS);              // (100 - (30+25))/100
        assertEquals(0.35, score.memoryScore(), EPS);           // (100 - (40+25))/100
        // 실효 전력 = 200 + (500-150)×0.25 = 287.5 → 여유 42.5%
        assertEquals(0.425, score.powerScore(), EPS);
        // 실효 온도 = 60 × (1 + k×0.25) → 여유 (75 - T)/75
        double effectiveTemp = 60 * (1 + inertiaK * 0.25);
        assertEquals(round4((75 - effectiveTemp) / 75), score.temperatureScore(), EPS);
    }

    @DisplayName("다건 연속 배치가 한 노드로 몰리지 않는다")
    @Test
    void placements_do_not_pile_up() {
        // 동일 사양 3대에 2코어 워크로드 6건 — 실측만 보면 [A,A,A,A,A,A]가 된다
        List<NodeResource> nodes = List.of(
                node("A", 8, 16384, 10, 20, 200, 500, 50),
                node("B", 8, 16384, 10, 20, 200, 500, 50),
                node("C", 8, 16384, 10, 20, 200, 500, 50));
        SchedulingPolicy policy = policy();
        PlacementContext context = PlacementContext.of(nodes, policy);

        Map<String, Integer> placed = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            Workload w = new Workload("w" + i, 2, 1024);
            SchedulingResult result = scheduler.schedule(w, context);
            assertTrue(result.success());
            context.reserve(result.selectedNodeId(), w);
            placed.merge(result.selectedNodeId(), 1, Integer::sum);
        }

        assertEquals(Map.of("A", 2, "B", 2, "C", 2), placed,
                "예약이 점수에 반영되면 라운드 로빈처럼 고르게 퍼져야 한다");
    }

    @DisplayName("네트워크 점수는 예약과 무관하게 실측을 유지한다")
    @Test
    void network_score_ignores_reservation() {
        NodeResource node = node("n1", 8, 16384, 30, 40, 200, 500, 55);
        PlacementContext context = PlacementContext.of(List.of(node), policy());

        double before = only(scheduler.schedule(new Workload("w", 1, 512), context)).networkScore();
        context.reserve("n1", new Workload("reserved", 4, 8192));
        double after = only(scheduler.schedule(new Workload("w2", 1, 512), context)).networkScore();

        assertEquals(before, after, EPS);
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private static SchedulingPolicy policy() {
        return new SchedulingPolicy("test", "test");
    }

    private static NodeScore only(SchedulingResult result) {
        assertEquals(1, result.candidateScores().size());
        return result.candidateScores().get(0);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static NodeResource node(String id, int cpu, long memMb, double cpuUsage, double memUsage,
                                     double power, double capacity, double temp) {
        return new NodeResource("mdc-01", id, cpu, memMb, 0, cpuUsage, memUsage, 40,
                power, capacity, temp, 1000, 500);
    }
}

package com.innogrid.aims.scheduler.model;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 배치 컨텍스트의 클러스터 전력 집계.
 *
 * <p>전력 최적화의 목표는 노드 하나의 점수가 아니라 <b>클러스터 총 전력</b>이다. 열 쪽에는
 * {@code averageTemperature()} 라는 클러스터 지표가 있었지만 전력에는 없었다.</p>
 */
class PlacementContextPowerTest {

    private static final double ACTIVE_CPU = 5.0;
    private final PowerModel powerModel = new PowerModel(new SchedulerProperties());

    @DisplayName("후보 전체의 소비 전력을 더한다")
    @Test
    void total_power_sums_candidates() {
        PlacementContext ctx = context(
                node("n1", 50, 250, 500),
                node("n2", 30, 180, 500),
                node("n3", 2, 120, 500));

        assertEquals(550.0, ctx.totalPowerWatts(), 1e-9);
    }

    @DisplayName("전력을 수집하지 못한 노드는 합계를 부풀리지 않는다")
    @Test
    void unmeasured_power_not_in_total() {
        PlacementContext ctx = context(
                node("n1", 50, 250, 500),
                node("n2", 30, 0, 500));      // IPMI 미연동

        assertEquals(250.0, ctx.totalPowerWatts(), 1e-9);
    }

    @DisplayName("기준 사용률을 넘는 노드를 가동 중으로 센다")
    @Test
    void above_threshold_counts_active() {
        PlacementContext ctx = context(
                node("busy", 50, 250, 500),
                node("light", 5.1, 160, 500),
                node("idle", 5.0, 150, 500),   // 기준값 자체는 가동 중이 아니다
                node("empty", 0, 150, 500));

        assertEquals(2, ctx.activeNodeCount(ACTIVE_CPU));
    }

    @DisplayName("비어 있는 노드의 유휴 전력을 반납 가능으로 본다")
    @Test
    void empty_node_power_reclaimable() {
        PlacementContext ctx = context(
                node("busy", 50, 250, 500),
                node("empty-1", 1, 150, 500),
                node("empty-2", 0, 150, 400));

        // 500×0.3 + 400×0.3 = 150 + 120
        assertEquals(270.0, ctx.reclaimableIdleWatts(ACTIVE_CPU, powerModel), 1e-9);
    }

    @DisplayName("전부 가동 중이면 반납할 전력이 없다")
    @Test
    void all_active_no_reclaimable() {
        PlacementContext ctx = context(
                node("n1", 50, 250, 500),
                node("n2", 40, 220, 500));

        assertEquals(0.0, ctx.reclaimableIdleWatts(ACTIVE_CPU, powerModel), 1e-9);
    }

    @DisplayName("후보가 비면 집계도 0이다")
    @Test
    void empty_candidates_zero_aggregates() {
        PlacementContext ctx = PlacementContext.of(List.of(), new SchedulingPolicy("t", "t"));

        assertEquals(0.0, ctx.totalPowerWatts(), 1e-9);
        assertEquals(0, ctx.activeNodeCount(ACTIVE_CPU));
        assertEquals(0.0, ctx.reclaimableIdleWatts(ACTIVE_CPU, powerModel), 1e-9);
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private PlacementContext context(NodeResource... nodes) {
        return PlacementContext.of(List.of(nodes), new SchedulingPolicy("test", "test"));
    }

    private NodeResource node(String nodeId, double cpuUsage, double power, double capacity) {
        return new NodeResource("mdc-01", nodeId, 8, 16384, 0,
                cpuUsage, 40, 30, power, capacity, 45, 1000, 500);
    }
}

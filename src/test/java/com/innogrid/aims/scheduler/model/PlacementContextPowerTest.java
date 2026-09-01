package com.innogrid.aims.scheduler.model;

import java.util.List;

import org.junit.jupiter.api.Test;

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
    private static final double IDLE_RATIO = 0.3;

    @Test
    void 후보_전체의_소비_전력을_더한다() {
        PlacementContext ctx = context(
                node("n1", 50, 250, 500),
                node("n2", 30, 180, 500),
                node("n3", 2, 120, 500));

        assertEquals(550.0, ctx.totalPowerWatts(), 1e-9);
    }

    @Test
    void 전력을_수집하지_못한_노드는_합계를_부풀리지_않는다() {
        PlacementContext ctx = context(
                node("n1", 50, 250, 500),
                node("n2", 30, 0, 500));      // IPMI 미연동

        assertEquals(250.0, ctx.totalPowerWatts(), 1e-9);
    }

    @Test
    void 기준_사용률을_넘는_노드를_가동_중으로_센다() {
        PlacementContext ctx = context(
                node("busy", 50, 250, 500),
                node("light", 5.1, 160, 500),
                node("idle", 5.0, 150, 500),   // 기준값 자체는 가동 중이 아니다
                node("empty", 0, 150, 500));

        assertEquals(2, ctx.activeNodeCount(ACTIVE_CPU));
    }

    @Test
    void 비어_있는_노드의_유휴_전력을_반납_가능으로_본다() {
        PlacementContext ctx = context(
                node("busy", 50, 250, 500),
                node("empty-1", 1, 150, 500),
                node("empty-2", 0, 150, 400));

        // 500×0.3 + 400×0.3 = 150 + 120
        assertEquals(270.0, ctx.reclaimableIdleWatts(ACTIVE_CPU, IDLE_RATIO), 1e-9);
    }

    @Test
    void 전부_가동_중이면_반납할_전력이_없다() {
        PlacementContext ctx = context(
                node("n1", 50, 250, 500),
                node("n2", 40, 220, 500));

        assertEquals(0.0, ctx.reclaimableIdleWatts(ACTIVE_CPU, IDLE_RATIO), 1e-9);
    }

    @Test
    void 후보가_비면_집계도_0이다() {
        PlacementContext ctx = PlacementContext.of(List.of(), new SchedulingPolicy("t", "t"));

        assertEquals(0.0, ctx.totalPowerWatts(), 1e-9);
        assertEquals(0, ctx.activeNodeCount(ACTIVE_CPU));
        assertEquals(0.0, ctx.reclaimableIdleWatts(ACTIVE_CPU, IDLE_RATIO), 1e-9);
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

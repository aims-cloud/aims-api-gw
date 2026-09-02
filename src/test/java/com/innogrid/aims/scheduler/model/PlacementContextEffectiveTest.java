package com.innogrid.aims.scheduler.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 컨텍스트의 클러스터 지표가 예약분(실효)을 반영하는지.
 *
 * <p>{@code PowerModel.isActive} 는 실효 사용률인데 클러스터 요약만 실측이면, 같은 응답 안에서
 * 노드별 "가동중"과 요약의 "가동 노드 수"가 어긋난다.</p>
 */
class PlacementContextEffectiveTest {

    private static final double ACTIVE_CPU = 5.0;
    private static final double IDLE_RATIO = 0.3;

    private final ThermalModel thermalModel = new ThermalModel(new SchedulerProperties());
    private final double inertiaK = new SchedulerProperties().getThermal().getInertiaK();

    @Test
    void 이번_실행에서_깨어난_빈_노드는_가동_중으로_센다() {
        PlacementContext ctx = context(node("busy", 50, 45), node("empty", 1, 45));
        assertEquals(1, ctx.activeNodeCount(ACTIVE_CPU));
        assertEquals(150.0, ctx.reclaimableIdleWatts(ACTIVE_CPU, IDLE_RATIO), 1e-9);

        ctx.reserve("empty", new Workload("w", 1, 512));    // 1/8 코어 → 실효 13.5%

        assertEquals(2, ctx.activeNodeCount(ACTIVE_CPU), "예약으로 깨어난 노드는 가동 중이다");
        assertEquals(0.0, ctx.reclaimableIdleWatts(ACTIVE_CPU, IDLE_RATIO), 1e-9,
                "깨어난 노드의 유휴 전력은 더 이상 반납 가능하지 않다");
    }

    @Test
    void 평균_온도는_실효_온도의_평균이다() {
        PlacementContext ctx = context(node("A", 30, 50), node("B", 30, 50));
        assertEquals(50.0, ctx.averageTemperature(thermalModel), 1e-9);   // 예약 0 → 실측 평균

        ctx.reserve("A", new Workload("w", 4, 4096));                     // 50% → 50×(1+k×0.5)

        double effA = 50 * (1 + inertiaK * 0.5);
        assertEquals((effA + 50) / 2, ctx.averageTemperature(thermalModel), 1e-9);
    }

    @Test
    void 온도를_수집하지_못한_노드는_예약이_있어도_0으로_평균에_들어간다() {
        PlacementContext ctx = context(node("A", 30, 0), node("B", 30, 60));
        ctx.reserve("A", new Workload("w", 4, 4096));

        assertEquals(30.0, ctx.averageTemperature(thermalModel), 1e-9,   // (0 + 60)/2
                "없는 값에 상승분을 얹지 않는다");
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private PlacementContext context(NodeResource... nodes) {
        return PlacementContext.of(List.of(nodes), new SchedulingPolicy("test", "test"));
    }

    private NodeResource node(String nodeId, double cpuUsage, double temp) {
        return new NodeResource("mdc-01", nodeId, 8, 16384, 0,
                cpuUsage, 40, 30, 200, 500, temp, 1000, 500);
    }
}

package com.innogrid.aims.scheduler.model;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 컨텍스트의 클러스터 지표가 예약분(실효)을 반영하는지.
 *
 * <p>{@code PowerModel.isActive} 는 실효 사용률인데 클러스터 요약만 실측이면, 같은 응답 안에서
 * 노드별 "가동중"과 요약의 "가동 노드 수"가 어긋난다.</p>
 *
 * <p>{@code averageTemperature} 의 평균 대상도 "후보 전체"에서 "계측된 후보"로 바뀌었다 —
 * 온도를 수집하지 못한 노드(0℃)는 평균에서 제외한다.</p>
 */
class PlacementContextEffectiveTest {

    private static final double ACTIVE_CPU = 5.0;

    private final ThermalModel thermalModel = new ThermalModel(new SchedulerProperties());
    private final double inertiaK = new SchedulerProperties().getThermal().getInertiaK();
    private final PowerModel powerModel = new PowerModel(new SchedulerProperties());

    @DisplayName("이번 실행에서 깨어난 빈 노드는 가동 중으로 센다")
    @Test
    void woken_node_counts_active() {
        PlacementContext ctx = context(node("busy", 50, 45), node("empty", 1, 45));
        assertEquals(1, ctx.activeNodeCount(ACTIVE_CPU));
        // empty 는 계측 200W·CPU 1% 라 반납 가능 전력을 실측에서 역산한다: (200 − 500×0.01)/(1−0.01) = 197.0W
        assertEquals((200 - 500 * 0.01) / 0.99, ctx.reclaimableIdleWatts(ACTIVE_CPU, powerModel), 1e-9);

        ctx.reserve("empty", new Workload("w", 1, 512));    // 1/8 코어 → 실효 13.5%

        assertEquals(2, ctx.activeNodeCount(ACTIVE_CPU), "예약으로 깨어난 노드는 가동 중이다");
        assertEquals(0.0, ctx.reclaimableIdleWatts(ACTIVE_CPU, powerModel), 1e-9,
                "깨어난 노드의 유휴 전력은 더 이상 반납 가능하지 않다");
    }

    @DisplayName("평균 온도는 실효 온도의 평균이다")
    @Test
    void average_of_effective_temps() {
        PlacementContext ctx = context(node("A", 30, 50), node("B", 30, 50));
        assertEquals(50.0, ctx.averageTemperature(thermalModel), 1e-9);   // 예약 0 → 실측 평균

        ctx.reserve("A", new Workload("w", 4, 4096));                     // 50% → 50×(1+k×0.5)

        double effA = 50 * (1 + inertiaK * 0.5);
        assertEquals((effA + 50) / 2, ctx.averageTemperature(thermalModel), 1e-9);
    }

    /**
     * 균형 점수는 {@code 1 − 0.5 × T / 평균} 형태라, 평균이 낮아지면 0으로 잘리는
     * 임계값(평균의 두 배)도 함께 낮아진다. 미계측 노드(0℃)를 평균에 넣으면 평균이
     * 실제보다 낮게 잡혀, 정상 범위인 계측 노드까지 부당하게 감점받는다.
     * 그래서 미계측 노드는 평균 계산에서 뺀다.
     */
    @DisplayName("온도를 수집하지 못한 노드는 평균에서 빠진다")
    @Test
    void unmeasured_temp_excluded_from_average() {
        PlacementContext ctx = context(node("A", 30, 0), node("B", 30, 60));
        ctx.reserve("A", new Workload("w", 4, 4096));    // 예약이 있어도 미계측이면 제외된다

        assertEquals(60.0, ctx.averageTemperature(thermalModel), 1e-9,   // 계측 노드는 B 하나뿐
                "0℃는 시원한 노드가 아니라 읽지 못한 값이다 — 평균에 넣으면 계측 노드가 실제보다 뜨겁게 보인다");
    }

    /**
     * 미계측 노드를 걸러내는 필터 때문에 계측 노드가 하나도 남지 않으면 스트림이
     * 비어 분모가 0이 된다 — 이 테스트가 그 경계(0/0)를 지킨다.
     */
    @DisplayName("계측된 노드가 하나도 없으면 평균은 0이다")
    @Test
    void all_unmeasured_average_is_0() {
        PlacementContext ctx = context(node("A", 30, 0), node("B", 30, 0));

        assertEquals(0.0, ctx.averageTemperature(thermalModel), 1e-9,
                "나눌 것이 없으면 0이다 — NaN이 되면 균형 점수가 통째로 NaN이 된다");
    }

    @DisplayName("제외 노드는 기본적으로 비어 있고 실어 주면 후보와 분리되어 보인다")
    @Test
    void excluded_empty_and_separate() {
        PlacementContext ctx = context(node("A", 30, 50));
        assertEquals(List.of(), ctx.excludedNodes());

        ctx.withExcluded(List.of(new ExcludedNode("mdc-01", "B", List.of("cpu사용률"))));

        assertEquals(1, ctx.excludedNodes().size());
        assertEquals(1, ctx.nodes().size(), "제외 노드는 후보에 섞이지 않는다");
        assertEquals("핵심 메트릭 판단 불가: cpu사용률 (질의 실패 또는 시리즈 없음)",
                ctx.excludedNodes().get(0).reason());
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

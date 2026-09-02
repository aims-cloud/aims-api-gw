package com.innogrid.aims.scheduler.engine.model;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;

class PowerModelTest {

    private final PowerModel model = new PowerModel(new SchedulerProperties());

    @Test
    void PEI는_단위_전력당_활용률을_정규화한다() {
        // 50% / 200W = 0.25 %/W, 기준 0.5 %/W → 0.5
        NodeResource node = node(50, 200, 500);

        assertEquals(0.25, model.rawPei(node), 1e-9);
        assertEquals(0.5, model.normalizedPei(node), 1e-9);
    }

    @Test
    void PEI는_1을_넘지_않는다() {
        NodeResource node = node(90, 100, 500); // 0.9 %/W → 1.8 이지만 상한 1.0

        assertEquals(1.0, model.normalizedPei(node), 1e-9);
    }

    @Test
    void 전력을_수집하지_못하면_중립값을_준다() {
        NodeResource node = node(50, 0, 500);

        assertEquals(0.5, model.normalizedPei(node), 1e-9);
        assertEquals(0.0, model.rawPei(node), 1e-9);
    }

    @Test
    void 이상적인_전력_곡선과_일치하면_비례성이_1이다() {
        // P_idle = 500 × 0.3 = 150, util 0.5 → P_ideal = 150 + 350×0.5 = 325
        NodeResource node = node(50, 325, 500);

        assertEquals(1.0, model.proportionality(node), 1e-9);
    }

    @Test
    void 유휴인데_전력을_많이_쓰면_비례성이_낮다() {
        NodeResource idleButHungry = node(5, 400, 500);
        NodeResource proportional = node(5, 167, 500);

        assertTrue(model.proportionality(idleButHungry) < model.proportionality(proportional));
    }

    @Test
    void 한계_전력은_요구_CPU_비율에_비례한다() {
        // (500 - 150) × (4/8) = 175W
        NodeResource node = node(20, 200, 500);

        assertEquals(175.0, model.marginalPower(node, 4), 1e-9);
        assertEquals(375.0, model.predictedPower(node, 4), 1e-9);
    }

    @Test
    void 전력_용량은_설정_기본값을_쓰고_노드별로_덮어쓸_수_있다() {
        SchedulerProperties props = new SchedulerProperties();
        props.getPower().setDefaultCapacityWatts(700);
        props.getPower().getCapacityByNode().put("node-special", 1200.0);
        PowerModel custom = new PowerModel(props);

        assertEquals(700.0, custom.capacityOf("node-1"), 1e-9);
        assertEquals(1200.0, custom.capacityOf("node-special"), 1e-9);
    }

    @Test
    void 예약이_없으면_실효_전력은_실측값과_같다() {
        assertEquals(250.0, model.effectivePowerWatts(node(40, 250, 500)), 1e-9);
    }

    @Test
    void 실효_전력은_예약분의_동적_전력을_더한다() {
        NodeResource node = node(40, 250, 500);

        node.reserve(2, 0, 0);

        // P_idle = 500 x 0.3 = 150, 동적 범위 350W, 8코어 중 2코어 = 0.25 → +87.5W
        assertEquals(337.5, model.effectivePowerWatts(node), 1e-9);
    }

    @Test
    void 전력을_수집하지_못한_노드는_예약이_있어도_0을_유지한다() {
        NodeResource node = node(40, 0, 500);

        node.reserve(4, 0, 0);

        assertEquals(0.0, model.effectivePowerWatts(node), 1e-9,
                "없는 전력에 예약분을 얹으면 근거 없는 숫자가 생긴다");
    }

    // ── 배치 점수용 지표 ────────────────────────────────────────

    /**
     * 결함 A — 이상 곡선보다 전력을 적게 쓰는 노드를 벌하면 안 된다.
     * 진단용 proportionality 와 값이 갈리는 지점이라 나란히 확인한다.
     */
    @Test
    void 비낭비는_이상_곡선을_넘어선_만큼만_감점한다() {
        // 용량 500W, CPU 60% → 이상 전력 150 + 350×0.6 = 360W
        assertEquals(1.0, model.wasteScore(node(60, 200, 500)), 1e-9, "덜 쓰는 것은 벌하지 않는다");
        assertEquals(1.0, model.wasteScore(node(60, 360, 500)), 1e-9, "이상값과 같으면 만점");
        assertEquals(0.84, model.wasteScore(node(60, 440, 500)), 1e-9, "80W 초과 / 500W");
        assertEquals(0.72, model.wasteScore(node(60, 500, 500)), 1e-9, "140W 초과 / 500W");
    }

    @Test
    void 비낭비는_진단_지표와_방향이_다르다() {
        NodeResource frugal = node(60, 200, 500);      // 매우 효율적
        NodeResource wasteful = node(60, 500, 500);    // 심한 낭비

        assertTrue(model.proportionality(frugal) < model.proportionality(wasteful),
                "대칭 지표는 효율적인 노드를 더 낮게 본다 — 진단용으로는 맞다");
        assertTrue(model.wasteScore(frugal) > model.wasteScore(wasteful),
                "배치용 단방향 지표는 효율적인 노드를 더 높게 봐야 한다");
    }

    @Test
    void 전력을_수집하지_못하면_비낭비는_중립값이다() {
        assertEquals(0.5, model.wasteScore(node(60, 0, 500)), 1e-9);
    }

    /** 활성 판정 기준은 설정값(기본 5%)이다. */
    @Test
    void 활성_노드_판정은_CPU_기준을_따른다() {
        assertTrue(model.isActive(node(5.1, 200, 500)));
        assertFalse(model.isActive(node(5.0, 200, 500)), "기준값 자체는 활성이 아니다");
        assertFalse(model.isActive(node(0, 200, 500)));
    }

    /**
     * M3 — 빈 노드에 처음 얹으면 그 노드의 유휴 전력이 통째로 새로 든다.
     * 이것이 전력 통합을 임의 보너스가 아닌 물리량으로 만든다.
     */
    @Test
    void 실질_한계전력은_빈_노드에_유휴전력을_더한다() {
        // 8코어 중 2코어 = 0.25, 동적 범위 350W → 동적 증가분 87.5W, 유휴 150W
        assertEquals(87.5, model.realMarginalPower(node(50, 250, 500), 2), 1e-9, "가동 중");
        assertEquals(237.5, model.realMarginalPower(node(2, 250, 500), 2), 1e-9, "빈 노드 — 깨우는 비용 포함");
    }

    @Test
    void 실질_한계전력_점수는_용량_대비로_정규화한다() {
        assertEquals(1.0 - 87.5 / 500, model.marginalScore(node(50, 250, 500), 2), 1e-9);
        assertEquals(1.0 - 237.5 / 500, model.marginalScore(node(2, 250, 500), 2), 1e-9);
    }

    /** 회귀 안전판 — α 기본값에서는 기존 선형 모델과 값이 같아야 한다. */
    @Test
    void 볼록_지수가_1이면_기존_한계전력과_같다() {
        NodeResource node = node(40, 250, 500);

        assertEquals(model.marginalPower(node, 4), model.marginalPowerOnCurve(node, 4), 1e-9);
        assertEquals(175.0, model.marginalPowerOnCurve(node, 4), 1e-9);
    }

    /**
     * 결함 C — 기존 한계 전력은 현재 부하를 보지 않아 같은 사양이면 모든 노드가 같은 값이었다.
     * 볼록 지수를 올리면 부하에 따라 갈린다.
     */
    @Test
    void 볼록_지수를_올리면_현재_부하에_반응한다() {
        SchedulerProperties props = new SchedulerProperties();
        props.getPower().setCurveAlpha(1.8);
        PowerModel convex = new PowerModel(props);

        double busy = convex.marginalPowerOnCurve(node(50, 250, 500), 2);
        double light = convex.marginalPowerOnCurve(node(20, 250, 500), 2);
        double idle = convex.marginalPowerOnCurve(node(2, 250, 500), 2);

        assertTrue(busy > light, "바쁜 노드에 얹으면 전력이 더 든다: " + busy + " vs " + light);
        assertTrue(light > idle, light + " vs " + idle);

        // 같은 입력을 기본 α 로 계산하면 셋이 모두 같다 — 그래서 구별력이 없었다
        assertEquals(model.marginalPowerOnCurve(node(50, 250, 500), 2),
                model.marginalPowerOnCurve(node(20, 250, 500), 2), 1e-9);
    }

    /** 사용률이 100% 를 넘어가지 않도록 자른다. */
    @Test
    void 요구량이_남은_용량보다_커도_100퍼센트를_넘지_않는다() {
        NodeResource node = node(90, 250, 500);

        double dp = model.marginalPowerOnCurve(node, 8);   // 8코어 전부 요구
        assertEquals(350 * 0.10, dp, 1e-9, "90% -> 100% 만큼만 는다");
    }

    private NodeResource node(double cpuUsage, double powerWatts, double capacityWatts) {
        return new NodeResource("mdc-01", "node-1", 8, 16384, 0,
                cpuUsage, 30, 30, powerWatts, capacityWatts, 50, 1000, 500);
    }
}

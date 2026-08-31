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

    private NodeResource node(double cpuUsage, double powerWatts, double capacityWatts) {
        return new NodeResource("mdc-01", "node-1", 8, 16384, 0,
                cpuUsage, 30, 30, powerWatts, capacityWatts, 50, 1000, 500);
    }
}

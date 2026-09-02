package com.innogrid.aims.scheduler.engine.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.NodeResource.ThermalState;

import static org.junit.jupiter.api.Assertions.*;

class ThermalModelTest {

    private final ThermalModel model = new ThermalModel(new SchedulerProperties());

    @Test
    void 열적_여유는_임계에_가까울수록_낮다() {
        assertTrue(model.headroomScore(40, 80) > model.headroomScore(70, 80));
        assertEquals(0.5, model.headroomScore(40, 80), 1e-9);
        assertEquals(0.0, model.headroomScore(90, 80), 1e-9, "임계를 넘어도 음수가 되지 않는다");
    }

    @Test
    void 냉각_점수는_제곱_비용을_반영한다() {
        // (40/80)^2 = 0.25 → 0.75
        assertEquals(0.75, model.coolingScore(40, 80), 1e-9);
        // 온도가 2배가 되면 비용은 4배가 되어 점수 하락 폭이 커진다
        assertTrue(model.coolingScore(40, 80) - model.coolingScore(60, 80)
                < model.coolingScore(60, 80) - model.coolingScore(80, 80));
    }

    @Test
    void 열적_균형은_평균보다_시원한_노드에_높은_점수를_준다() {
        double cooler = model.balanceScore(50, 60);
        double average = model.balanceScore(60, 60);
        double hotter = model.balanceScore(70, 60);

        assertTrue(cooler > average);
        assertTrue(average > hotter);
        assertEquals(0.5, average, 1e-9);
    }

    @Test
    void 열적_균형은_0과_1_사이로_제한된다() {
        assertEquals(0.0, model.balanceScore(200, 50), 1e-9);
        assertEquals(1.0, model.balanceScore(0, 50), 1e-9);
    }

    @Test
    void 배치_후_예상_온도를_열관성으로_추정한다() {
        // 60 + 0.15 × (4/8) × 60 = 64.5
        NodeResource node = node(60);

        assertEquals(64.5, model.predictedTemperature(node, 4), 1e-9);
    }

    @Test
    void 온도_상태를_경고와_임계로_구분한다() {
        assertEquals(ThermalState.NORMAL, model.state(50, 65, 75));
        assertEquals(ThermalState.WARNING, model.state(65, 65, 75));
        assertEquals(ThermalState.WARNING, model.state(74, 65, 75));
        assertEquals(ThermalState.CRITICAL, model.state(75, 65, 75));
    }

    @Test
    void 온도를_수집하지_못한_노드는_정상으로_본다() {
        assertEquals(ThermalState.NORMAL, model.state(0, 65, 75));
    }

    @Test
    void 경고_구간_감점은_임계에_가까울수록_커진다() {
        assertEquals(0.0, model.warningPenalty(60, 65, 75), 1e-9);
        assertEquals(0.0, model.warningPenalty(65, 65, 75), 1e-9);
        assertEquals(0.5, model.warningPenalty(70, 65, 75), 1e-9);
        assertEquals(1.0, model.warningPenalty(75, 65, 75), 1e-9);
    }

    @Test
    void 임계에_도달한_노드는_쿨다운_시간_동안_제외된다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T00:00:00Z"));
        ThermalModel cooling = new ThermalModel(new SchedulerProperties(), clock);

        assertFalse(cooling.isCoolingDown("node-1"));

        cooling.markCritical("node-1");
        assertTrue(cooling.isCoolingDown("node-1"));
        assertEquals(10, cooling.remainingCooldownMinutes("node-1"));

        clock.advance(Duration.ofMinutes(4));
        assertTrue(cooling.isCoolingDown("node-1"), "아직 쿨다운 중이다");
        assertEquals(6, cooling.remainingCooldownMinutes("node-1"));

        clock.advance(Duration.ofMinutes(6));
        assertFalse(cooling.isCoolingDown("node-1"), "10분이 지나면 해제된다");
    }

    @Test
    void 쿨다운은_수동으로도_해제할_수_있다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T00:00:00Z"));
        ThermalModel cooling = new ThermalModel(new SchedulerProperties(), clock);

        cooling.markCritical("node-1");
        cooling.clearCritical("node-1");

        assertFalse(cooling.isCoolingDown("node-1"));
    }

    @Test
    void 예약이_없으면_실효_온도는_실측값과_같다() {
        assertEquals(60.0, model.effectiveTemperature(node(60)), 1e-9);
    }

    @Test
    void 실효_온도는_예약분의_상승을_더한다() {
        NodeResource node = node(60);

        node.reserve(2, 0, 0);

        // k=0.15, 8코어 중 2코어 = 0.25 → 60 x (1 + 0.0375) = 62.25
        assertEquals(62.25, model.effectiveTemperature(node), 1e-9);
    }

    @Test
    void 온도를_수집하지_못한_노드는_예약이_있어도_0을_유지한다() {
        NodeResource node = node(0);

        node.reserve(4, 0, 0);

        assertEquals(0.0, model.effectiveTemperature(node), 1e-9);
    }

    private NodeResource node(double temperature) {
        return new NodeResource("mdc-01", "node-1", 8, 16384, 0,
                30, 30, 30, 200, 500, temperature, 1000, 500);
    }

    /** 쿨다운 검증을 위해 시간을 앞으로 돌릴 수 있는 시계. */
    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { this.now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

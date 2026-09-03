package com.innogrid.aims.scheduler.engine.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.NodeResource.ThermalState;

import static org.junit.jupiter.api.Assertions.*;

class ThermalModelTest {

    private final ThermalModel model = new ThermalModel(new SchedulerProperties());

    @DisplayName("열적 여유는 임계에 가까울수록 낮다")
    @Test
    void headroom_lower_near_threshold() {
        assertTrue(model.headroomScore(40, 80) > model.headroomScore(70, 80));
        assertEquals(0.5, model.headroomScore(40, 80), 1e-9);
        assertEquals(0.0, model.headroomScore(90, 80), 1e-9, "임계를 넘어도 음수가 되지 않는다");
    }

    @DisplayName("냉각 점수는 제곱 비용을 반영한다")
    @Test
    void cooling_score_squared_cost() {
        // (40/80)^2 = 0.25 → 0.75
        assertEquals(0.75, model.coolingScore(40, 80), 1e-9);
        // 온도가 2배가 되면 비용은 4배가 되어 점수 하락 폭이 커진다
        assertTrue(model.coolingScore(40, 80) - model.coolingScore(60, 80)
                < model.coolingScore(60, 80) - model.coolingScore(80, 80));
    }

    @DisplayName("열적 균형은 평균보다 시원한 노드에 높은 점수를 준다")
    @Test
    void balance_favors_cooler_nodes() {
        double cooler = model.balanceScore(50, 60);
        double average = model.balanceScore(60, 60);
        double hotter = model.balanceScore(70, 60);

        assertTrue(cooler > average);
        assertTrue(average > hotter);
        assertEquals(0.5, average, 1e-9);
    }

    @DisplayName("열적 균형은 0과 1 사이로 제한된다")
    @Test
    void balance_clamped_0_to_1() {
        assertEquals(0.0, model.balanceScore(200, 50), 1e-9);
        assertEquals(1.0, model.balanceScore(0, 50), 1e-9);
    }

    @DisplayName("배치 후 예상 온도를 열관성으로 추정한다")
    @Test
    void predicted_temp_by_thermal_inertia() {
        // 60 + 0.15 × (4/8) × 60 = 64.5
        NodeResource node = node(60);

        assertEquals(64.5, model.predictedTemperature(node, 4), 1e-9);
    }

    @DisplayName("온도 상태를 경고와 임계로 구분한다")
    @Test
    void temp_status_warning_and_critical() {
        assertEquals(ThermalState.NORMAL, model.state(50, 65, 75));
        assertEquals(ThermalState.WARNING, model.state(65, 65, 75));
        assertEquals(ThermalState.WARNING, model.state(74, 65, 75));
        assertEquals(ThermalState.CRITICAL, model.state(75, 65, 75));
    }

    @DisplayName("온도를 수집하지 못한 노드는 정상으로 본다")
    @Test
    void unmeasured_temp_treated_normal() {
        assertEquals(ThermalState.NORMAL, model.state(0, 65, 75));
    }

    @DisplayName("경고 구간 감점은 임계에 가까울수록 커진다")
    @Test
    void warning_deduction_grows_near_threshold() {
        assertEquals(0.0, model.warningPenalty(60, 65, 75), 1e-9);
        assertEquals(0.0, model.warningPenalty(65, 65, 75), 1e-9);
        assertEquals(0.5, model.warningPenalty(70, 65, 75), 1e-9);
        assertEquals(1.0, model.warningPenalty(75, 65, 75), 1e-9);
    }

    @DisplayName("임계에 도달한 노드는 쿨다운 시간 동안 제외된다")
    @Test
    void threshold_node_excluded_in_cooldown() {
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

    @DisplayName("쿨다운은 수동으로도 해제할 수 있다")
    @Test
    void cooldown_cleared_manually() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T00:00:00Z"));
        ThermalModel cooling = new ThermalModel(new SchedulerProperties(), clock);

        cooling.markCritical("node-1");
        cooling.clearCritical("node-1");

        assertFalse(cooling.isCoolingDown("node-1"));
    }

    @DisplayName("예약이 없으면 실효 온도는 실측값과 같다")
    @Test
    void no_reservation_temp_measured() {
        assertEquals(60.0, model.effectiveTemperature(node(60)), 1e-9);
    }

    @DisplayName("실효 온도는 예약분의 상승을 더한다")
    @Test
    void effective_temp_adds_reservation_rise() {
        NodeResource node = node(60);

        node.reserve(2, 0, 0);

        // k=0.15, 8코어 중 2코어 = 0.25 → 60 x (1 + 0.0375) = 62.25
        assertEquals(62.25, model.effectiveTemperature(node), 1e-9);
    }

    @DisplayName("온도를 수집하지 못한 노드는 예약이 있어도 0을 유지한다")
    @Test
    void unmeasured_temp_stays_0() {
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

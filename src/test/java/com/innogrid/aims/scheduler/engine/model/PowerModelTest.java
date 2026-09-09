package com.innogrid.aims.scheduler.engine.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;

class PowerModelTest {

    private final PowerModel model = new PowerModel(new SchedulerProperties());

    @DisplayName("PEI는 단위 전력당 활용률을 정규화한다")
    @Test
    void pei_normalizes_per_unit_power() {
        // 50% / 200W = 0.25 %/W, 기준 0.5 %/W → 0.5
        NodeResource node = node(50, 200, 500);

        assertEquals(0.25, model.rawPei(node), 1e-9);
        assertEquals(0.5, model.normalizedPei(node), 1e-9);
    }

    @DisplayName("PEI는 1을 넘지 않는다")
    @Test
    void pei_does_not_exceed_1() {
        NodeResource node = node(90, 100, 500); // 0.9 %/W → 1.8 이지만 상한 1.0

        assertEquals(1.0, model.normalizedPei(node), 1e-9);
    }

    @DisplayName("전력을 수집하지 못하면 중립값을 준다")
    @Test
    void unmeasured_power_neutral_value() {
        NodeResource node = node(50, 0, 500);

        assertEquals(0.5, model.normalizedPei(node), 1e-9);
        assertEquals(0.0, model.rawPei(node), 1e-9);
    }

    @DisplayName("이상적인 전력 곡선과 일치하면 비례성이 1이다")
    @Test
    void ideal_curve_proportionality_1() {
        // P_idle = 500 × 0.3 = 150, util 0.5 → P_ideal = 150 + 350×0.5 = 325
        NodeResource node = node(50, 325, 500);

        assertEquals(1.0, model.proportionality(node), 1e-9);
    }

    @DisplayName("유휴인데 전력을 많이 쓰면 비례성이 낮다")
    @Test
    void idle_power_hog_low_proportionality() {
        NodeResource idleButHungry = node(5, 400, 500);
        NodeResource proportional = node(5, 167, 500);

        assertTrue(model.proportionality(idleButHungry) < model.proportionality(proportional));
    }

    @DisplayName("한계 전력은 요구 CPU 비율에 비례한다")
    @Test
    void marginal_power_scales_with_cpu() {
        // (500 - 150) × (4/8) = 175W
        NodeResource node = node(20, 200, 500);

        assertEquals(175.0, model.marginalPower(node, 4), 1e-9);
        assertEquals(375.0, model.predictedPower(node, 4), 1e-9);
    }

    @DisplayName("전력 용량은 설정 기본값을 쓰고 노드별로 덮어쓸 수 있다")
    @Test
    void power_capacity_default_and_override() {
        SchedulerProperties props = new SchedulerProperties();
        props.getPower().setDefaultCapacityWatts(700);
        props.getPower().getCapacityByNode().put("node-special", 1200.0);
        PowerModel custom = new PowerModel(props);

        assertEquals(700.0, custom.capacityOf("node-1"), 1e-9);
        assertEquals(1200.0, custom.capacityOf("node-special"), 1e-9);
    }

    @DisplayName("예약이 없으면 실효 전력은 실측값과 같다")
    @Test
    void no_reservation_power_measured() {
        assertEquals(250.0, model.effectivePowerWatts(node(40, 250, 500)), 1e-9);
    }

    @DisplayName("실효 전력은 예약분의 동적 전력을 더한다")
    @Test
    void effective_power_adds_reservation() {
        NodeResource node = node(40, 250, 500);

        node.reserve(2, 0, 0);

        // P_idle = 500 x 0.3 = 150, 동적 범위 350W, 8코어 중 2코어 = 0.25 → +87.5W
        assertEquals(337.5, model.effectivePowerWatts(node), 1e-9);
    }

    @DisplayName("전력을 수집하지 못한 노드는 예약이 있어도 0을 유지한다")
    @Test
    void unmeasured_power_stays_0() {
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
    @DisplayName("비낭비는 이상 곡선을 넘어선 만큼만 감점한다")
    @Test
    void waste_deducts_excess_over_ideal() {
        // 용량 500W, CPU 60% → 이상 전력 150 + 350×0.6 = 360W
        assertEquals(1.0, model.wasteScore(node(60, 200, 500)), 1e-9, "덜 쓰는 것은 벌하지 않는다");
        assertEquals(1.0, model.wasteScore(node(60, 360, 500)), 1e-9, "이상값과 같으면 만점");
        assertEquals(0.84, model.wasteScore(node(60, 440, 500)), 1e-9, "80W 초과 / 500W");
        assertEquals(0.72, model.wasteScore(node(60, 500, 500)), 1e-9, "140W 초과 / 500W");
    }

    @DisplayName("비낭비는 진단 지표와 방향이 다르다")
    @Test
    void waste_opposite_diagnostic_metric() {
        NodeResource frugal = node(60, 200, 500);      // 매우 효율적
        NodeResource wasteful = node(60, 500, 500);    // 심한 낭비

        assertTrue(model.proportionality(frugal) < model.proportionality(wasteful),
                "대칭 지표는 효율적인 노드를 더 낮게 본다 — 진단용으로는 맞다");
        assertTrue(model.wasteScore(frugal) > model.wasteScore(wasteful),
                "배치용 단방향 지표는 효율적인 노드를 더 높게 봐야 한다");
    }

    @DisplayName("전력을 수집하지 못하면 비낭비는 중립값이다")
    @Test
    void unmeasured_waste_neutral() {
        assertEquals(0.5, model.wasteScore(node(60, 0, 500)), 1e-9);
    }

    /** 활성 판정 기준은 설정값(기본 5%)이다. */
    @DisplayName("활성 노드 판정은 CPU 기준을 따른다")
    @Test
    void active_decision_by_cpu_threshold() {
        assertTrue(model.isActive(node(5.1, 200, 500)));
        assertFalse(model.isActive(node(5.0, 200, 500)), "기준값 자체는 활성이 아니다");
        assertFalse(model.isActive(node(0, 200, 500)));
    }

    /**
     * M3 — 빈 노드에 처음 얹으면 그 노드의 유휴 전력이 통째로 새로 든다.
     * 이것이 전력 통합을 임의 보너스가 아닌 물리량으로 만든다.
     */
    @DisplayName("실질 한계전력은 빈 노드에 유휴전력을 더한다")
    @Test
    void real_marginal_adds_idle_power() {
        // 8코어 중 2코어 = 0.25, 동적 범위 350W → 동적 증가분 87.5W, 유휴 150W
        assertEquals(87.5, model.realMarginalPower(node(50, 250, 500), 2), 1e-9, "가동 중");
        // node(2, 250, 500) 은 유휴(2% < 5%)이고 전력이 실측(250W)이라, 유휴 역산 경로가 켜져
        // 있는 기본 모델에서는 대체값(150W)이 아니라 실측 역산값을 깨우기 비용으로 쓴다.
        // 예전(대체값 시절)의 237.5 가 아니다 — 왜 바뀌었는지는 wakeupPower 역산식 그대로.
        double reconstructedWakeup = (250.0 - 500.0 * 0.02) / (1.0 - 0.02);
        assertEquals(87.5 + reconstructedWakeup, model.realMarginalPower(node(2, 250, 500), 2), 1e-9,
                "빈 노드 — 깨우는 비용을 실측에서 역산(대체값 237.5 아님)");
    }

    @DisplayName("실질 한계전력 점수는 용량 대비로 정규화한다")
    @Test
    void real_marginal_score_normalized() {
        assertEquals(1.0 - 87.5 / 500, model.marginalScore(node(50, 250, 500), 2), 1e-9);
        // real_marginal_adds_idle_power 와 같은 이유로 237.5 대신 역산값 기준.
        double reconstructedWakeup = (250.0 - 500.0 * 0.02) / (1.0 - 0.02);
        assertEquals(1.0 - (87.5 + reconstructedWakeup) / 500, model.marginalScore(node(2, 250, 500), 2), 1e-9);
    }

    /** 회귀 안전판 — α 기본값에서는 기존 선형 모델과 값이 같아야 한다. */
    @DisplayName("볼록 지수가 1이면 기존 한계전력과 같다")
    @Test
    void convex_exponent_1_unchanged() {
        NodeResource node = node(40, 250, 500);

        assertEquals(model.marginalPower(node, 4), model.marginalPowerOnCurve(node, 4), 1e-9);
        assertEquals(175.0, model.marginalPowerOnCurve(node, 4), 1e-9);
    }

    /**
     * 결함 C — 기존 한계 전력은 현재 부하를 보지 않아 같은 사양이면 모든 노드가 같은 값이었다.
     * 볼록 지수를 올리면 부하에 따라 갈린다.
     */
    @DisplayName("볼록 지수를 올리면 현재 부하에 반응한다")
    @Test
    void convex_exponent_reacts_to_load() {
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
    @DisplayName("요구량이 남은 용량보다 커도 100퍼센트를 넘지 않는다")
    @Test
    void demand_over_capacity_caps_at_100() {
        NodeResource node = node(90, 250, 500);

        double dp = model.marginalPowerOnCurve(node, 8);   // 8코어 전부 요구
        assertEquals(350 * 0.10, dp, 1e-9, "90% -> 100% 만큼만 는다");
    }

    // ── 깨우기 비용 실측 역산 ────────────────────────────────────

    @DisplayName("유휴 노드는 실측에서 선언 유휴전력을 역산한다")
    @Test
    void wakeup_power_recovers_declared_idle_from_a_measured_idle_node() {
        // 정격 500, 선언 유휴 100 을 가정한 생성식 P = 100 + 400·u, u=0.02 → P=108.
        // 대체값(정격 × idleRatio=0.3=150) 도, 보정 없는 실측 108 도 아니고 역산한 100.0 이어야 한다.
        NodeResource node = node(2, 108, 500);

        assertEquals(100.0, model.wakeupPower(node), 1e-9,
                "역산 없이 실측 108을 그대로 쓰면 안 된다");
    }

    @DisplayName("미계측·가동·범위 밖이면 대체값으로 돌아간다")
    @Test
    void wakeup_power_falls_back_for_unmeasured_active_or_out_of_range_nodes() {
        assertEquals(150.0, model.wakeupPower(node(2, 0, 500)), 1e-9, "전력 미계측");
        assertEquals(150.0, model.wakeupPower(node(40, 250, 500)), 1e-9, "가동 중이라 역산 불가");
        assertEquals(0.0, model.wakeupPower(node(2, 108, 0)), 1e-9, "정격 미상");
        // (5 - 500×0.02) / 0.98 = -5.10... < 0 → 계측 잡음으로 보고 대체값
        assertEquals(150.0, model.wakeupPower(node(2, 5, 500)), 1e-9, "역산이 음수");
        // (520 - 500×0.04) / 0.96 = 520.8 > 500(정격) → 계측 잡음으로 보고 대체값
        assertEquals(150.0, model.wakeupPower(node(4, 520, 500)), 1e-9, "역산이 정격을 넘음");
    }

    /** 추정이 정확히 0 인 경계 — 0W 유휴는 물리적으로 없으므로 유효값이 아니라 대체값이어야 한다. */
    @DisplayName("역산이 정확히 0 이면 유효값이 아니라 대체값이다")
    @Test
    void a_zero_estimate_is_not_a_valid_idle_power() {
        // (10 − 500×0.02) / 0.98 = 0 정확히 — 하한 가드가 <= 0 이어야 한다(< 0 이면 0W 유휴가 나간다)
        assertEquals(150.0, model.wakeupPower(node(2, 10, 500)), 1e-9, "0W 유휴 전력은 없다");
    }

    /**
     * 역산 입력은 <b>실측</b> CPU 다. 예약분을 섞으면 "같은 순간의 (P, u) 쌍"이 아니게 되어 식이 어긋난다.
     * 64코어 노드에 1코어를 예약하면 실효 CPU 는 1% → 2.56% 로 움직이지만(여전히 유휴), 실측 전력 104W 는 그대로다 —
     * 실측 u 로 역산하면 (104 − 500×0.01)/0.99 = 100, 실효 u 로 역산하면 93.6 이 나온다.
     */
    @DisplayName("역산은 예약분이 아니라 실측 CPU 로 한다")
    @Test
    void wakeup_power_reads_measured_cpu_not_effective_cpu() {
        NodeResource wide = new NodeResource("mdc-01", "wide", 64, 262144, 0,
                1, 30, 30, 104, 500, 50, 1000, 500);
        wide.reserve(1, 4096, 0);
        assertTrue(wide.effectiveCpuUsagePercent() > wide.getCpuUsagePercent(), "예약이 실효 CPU 를 올려야 시험이 성립한다");
        assertTrue(wide.effectiveCpuUsagePercent() <= 5.0, "여전히 유휴여야 역산 경로를 탄다");
        assertEquals(100.0, model.wakeupPower(wide), 1e-9,
                "실효 CPU 로 역산하면 93.6 — 예약은 그 순간의 실측 전력을 만든 부하가 아니다");
    }

    /**
     * 상한 등호 경계 — 역산값이 정격과 정확히 같으면 여전히 유효값이어야 한다. 가드가
     * {@code estimate > pMax} 가 아니라 {@code estimate >= pMax} 로 바뀌면 이 경계에서
     * 대체값(150)으로 조용히 떨어진다.
     */
    @DisplayName("추정이 정격과 정확히 같은 경계는 여전히 유효값이다")
    @Test
    void an_estimate_exactly_at_capacity_is_still_valid() {
        assertEquals(500.0, model.wakeupPower(node(0, 500, 500)), 1e-9);
    }

    @DisplayName("플래그를 끄면 유휴 역산도 대체값으로 돌아간다")
    @Test
    void wakeup_power_is_off_by_the_flag() {
        SchedulerProperties props = new SchedulerProperties();
        props.getPower().setIdleFromMeasurement(false);
        PowerModel withoutReconstruction = new PowerModel(props);

        assertEquals(150.0, withoutReconstruction.wakeupPower(node(2, 108, 500)), 1e-9);
    }

    @DisplayName("유휴전력과 진단 점수는 역산을 따라가지 않는다")
    @Test
    void idle_power_and_the_diagnostic_scores_do_not_follow_measurement() {
        SchedulerProperties onProps = new SchedulerProperties();
        SchedulerProperties offProps = new SchedulerProperties();
        offProps.getPower().setIdleFromMeasurement(false);
        PowerModel reconstructing = new PowerModel(onProps);
        PowerModel fixed = new PowerModel(offProps);
        NodeResource node = node(2, 108, 500);

        // idlePower 는 진단·동적 범위의 기준선이다 — 역산을 넣으면 항등식이 되어 구별력을 잃는다.
        assertEquals(150.0, reconstructing.idlePower(node), 1e-9);
        assertEquals(150.0, fixed.idlePower(node), 1e-9);
        assertEquals(fixed.proportionality(node), reconstructing.proportionality(node), 1e-9,
                "역산 여부와 무관하게 같아야 한다");
        assertEquals(fixed.wasteScore(node), reconstructing.wasteScore(node), 1e-9,
                "역산 여부와 무관하게 같아야 한다");
    }

    @DisplayName("실질 한계전력은 역산한 유휴전력으로 깨어난다")
    @Test
    void real_marginal_power_wakes_with_the_measured_idle() {
        // marginalOnCurve(α=1) = 350 × (2/8) = 87.5 — 동적 범위는 idlePower 기준 350 그대로.
        // 유휴는 대체값 150 이 아니라 역산값 100 → 87.5 + 100 = 187.5 (대체값 시절 237.5 아님).
        NodeResource node = node(2, 108, 500);

        assertEquals(87.5, model.marginalPowerOnCurve(node, 2), 1e-9);
        assertEquals(187.5, model.realMarginalPower(node, 2), 1e-9);
    }

    private NodeResource node(double cpuUsage, double powerWatts, double capacityWatts) {
        return new NodeResource("mdc-01", "node-1", 8, 16384, 0,
                cpuUsage, 30, 30, powerWatts, capacityWatts, 50, 1000, 500);
    }
}

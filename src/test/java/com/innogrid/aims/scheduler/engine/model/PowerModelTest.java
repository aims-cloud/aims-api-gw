package com.innogrid.aims.scheduler.engine.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;

class PowerModelTest {

    private final PowerModel model = new PowerModel(new SchedulerProperties());

    @DisplayName("PEI는 단위 전력당 활용률을 정규화한다")
    @Test
    void pei_normalizes_per_unit_power() {
        // 50% / 200W = 0.25%/W, 기준 0.5%/W → 0.5
        NodeResource node = node(50, 200, 500);

        assertEquals(0.25, model.rawPei(node), 1e-9);
        assertEquals(0.5, model.normalizedPei(node), 1e-9);
    }

    @DisplayName("PEI는 1을 넘지 않는다")
    @Test
    void pei_does_not_exceed_1() {
        NodeResource node = node(90, 100, 500); // 0.9%/W → 1.8이지만 상한 1.0

        assertEquals(1.0, model.normalizedPei(node), 1e-9);
    }

    @DisplayName("전력을 수집하지 못하면 중립값을 준다")
    @Test
    void unmeasured_power_neutral_value() {
        NodeResource node = node(50, 0, 500);

        assertEquals(0.5, model.normalizedPei(node), 1e-9);
        assertEquals(0.0, model.rawPei(node), 1e-9);
    }

    /** 이 파일의 손 검산 리터럴이 전제하는 설정 기본값 — 값이 바뀌면 여기서 먼저 멈춰 원인을 가리킨다. */
    @DisplayName("손 검산 리터럴이 전제하는 설정 기본값은 정규화 0.5 · 유휴비 0.3 · 곡선 α 1.0 이다")
    @Test
    void literals_assume_default_power_settings() {
        SchedulerProperties.Power defaults = new SchedulerProperties().getPower();
        assertEquals(0.5, defaults.getPeiNormalizer(), 0.0);
        assertEquals(0.3, defaults.getIdleRatio(), 0.0);
        assertEquals(1.0, defaults.getCurveAlpha(), 0.0);
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
     * 이상 곡선보다 전력을 적게 쓰는 노드를 벌하면 안 된다.
     * 진단용 proportionality와 값이 갈리는 지점이라 나란히 확인한다.
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

    // ── 배치 점수용 실효 기저(PEI·비낭비) ─────────────────────────

    /** 예약 0이면 실효 입력이 실측과 같아 두 쌍이 비트 단위로 같다 — 첫 배치는 불변. */
    @DisplayName("예약이 없으면 배치용 PEI·비낭비는 진단용과 비트 단위로 같다")
    @Test
    void placement_pei_and_waste_equal_diagnostics_without_reservation() {
        for (NodeResource n : List.of(node(40, 250, 500), node(60, 440, 500), node(2, 108, 500))) {
            assertEquals(model.normalizedPei(n), model.placementPei(n), 0.0, "예약 0: PEI 비트 동일");
            assertEquals(model.wasteScore(n), model.placementWasteScore(n), 0.0, "예약 0: 비낭비 비트 동일");
        }
    }

    /**
     * 비낭비 항의 실수 항등 — 예약분 D·r이 실효 전력과 이상 곡선 양쪽에 같이 들어가 상쇄된다. 클램프가 걸리지 않는(적격) 노드에서
     * 두 값은 같고, 그래도 배치 점수가 이 항을 실효로 읽는 이유는 한 식 안에서 항마다 입력이 갈리지 않게 하기 위함이다.
     */
    @DisplayName("예약이 있어도 적격 노드에서 배치용 비낭비는 진단용과 같다 — 예약분이 상쇄되는 실수 항등")
    @Test
    void placement_waste_is_identical_on_eligible_reserved_nodes() {
        NodeResource reserved = node(40, 440, 500);
        reserved.reserve(2, 0, 0);                                   // 실효 65%, 가용 8×0.6−2 = 2.8 ≥ 1: 적격
        NodeResource plain = node(40, 440, 500);

        assertEquals(model.wasteScore(plain), model.placementWasteScore(reserved), 1e-9, "D·r 상쇄");
        assertEquals(model.wasteScore(reserved), model.placementWasteScore(reserved), 1e-9, "진단용은 예약을 보지 않으니 같은 값");
    }

    /**
     * PEI는 예약이 쌓이면 움직인다 — 오르는 조건은 {@code 100·P > u·D}. 등록 판정 판의 노드는 전부 이 조건 안이라 상승하고,
     * 저전력·고정격으로 조건을 깬 합성 노드에서는 내려간다. 기저를 실측으로 되돌리면 두 값이 같아져 여기서 멈춘다.
     */
    @DisplayName("예약이 쌓인 적격 노드에서 배치용 PEI 는 100·P > u·D 이면 오르고 아니면 내린다")
    @Test
    void placement_pei_moves_with_reservation_by_sign_condition() {
        NodeResource rising = node(45, 387, 500);                   // 100·387 = 38700 > 45·350 = 15750 → 상승
        rising.reserve(1, 0, 0);
        NodeResource falling = node(60, 120, 1000);                 // 100·120 = 12000 < 60·700 = 42000 → 하락(합성 노드)
        falling.reserve(1, 0, 0);

        assertTrue(model.placementPei(rising) > model.normalizedPei(rising), "판정 판 대표 노드 — 예약이 쌓이면 PEI 상승");
        assertTrue(model.placementPei(falling) < model.normalizedPei(falling), "저전력·고정격 합성 노드 — 조건 밖에서는 하락");
        // 상승 폭의 식: ΔPEI = ((u+100r)/(P+D·r) − u/P) / normalizer
        double r = 1.0 / 8, u = 45, p = 387, d = 350;                // D = 정격 500 − 유휴 150 (리터럴 — idlePower 재호출로 검산하지 않는다)
        double expected = ((u + 100 * r) / (p + d * r) - u / p) / 0.5;
        assertEquals(expected, model.placementPei(rising) - model.normalizedPei(rising), 1e-9);
    }

    /** 실효 사용률이 100에서 잘리는 노드에서만 비낭비 항등이 깨지고, 그런 노드는 자원 제약이 이미 거른다. */
    @DisplayName("비낭비 항등이 깨지는 노드는 실효 사용률이 100 에 잘린 노드뿐이고 그 노드는 배치 대상이 아니다")
    @Test
    void placement_waste_diverges_only_on_clamped_ineligible_nodes() {
        NodeResource clamped = node(90, 440, 500);
        clamped.reserve(2, 0, 0);                                    // 실효 115 → 100 클램프, 가용 8×0.1−2 < 0

        assertEquals(100.0, clamped.effectiveCpuUsagePercent(), 1e-9);
        assertTrue(clamped.availableCpu() < 1, "가용 코어가 없어 어떤 요구도 수용하지 못한다");
        assertNotEquals(model.wasteScore(clamped), model.placementWasteScore(clamped), "클램프 노드에서는 두 값이 갈린다");
    }

    /** 유휴 노드에 첫 예약이 잡히면 실효 경로 둘(PEI·가동 판정)이 같이 움직인다 — 14.5 / 151.75 / 0.5. */
    @DisplayName("유휴 노드에 예약이 잡히면 배치용 PEI 가 실효 값으로 오르고 가동 판정도 같은 예약을 본다")
    @Test
    void idle_node_with_reservation_moves_placement_pei_and_activity_together() {
        NodeResource idle = node(2, 108, 500);
        assertFalse(model.isActive(idle), "예약 0: 유휴");
        idle.reserve(1, 0, 0);                                       // 실효 14.5%, P_eff 108 + 350 × 0.125 = 151.75

        assertEquals(0.1911038, model.placementPei(idle), 1e-7, "설정 기본값(정규화 0.5 · 유휴비 0.3) 전제");
        assertEquals(0.0370370, model.normalizedPei(idle), 1e-7, "진단용은 그대로 2 / 108 / 0.5");
        assertTrue(model.isActive(idle), "같은 예약으로 가동 판정도 뒤집힌다");
    }

    @DisplayName("배치용 PEI 도 정규화 뒤 1.0 에서 포화한다")
    @Test
    void placement_pei_saturates_at_one() {
        NodeResource frugal = node(90, 10, 500);                     // 90/10 = 9%/W ≫ normalizer 0.5
        assertEquals(1.0, model.placementPei(frugal), 0.0);
        frugal.reserve(1, 0, 0);
        assertEquals(1.0, model.placementPei(frugal), 0.0, "예약이 쌓여도 포화 상한은 그대로");
    }

    @DisplayName("배치용 PEI·비낭비도 전력 미계측이면 중립값이다")
    @Test
    void placement_metrics_are_neutral_without_power() {
        NodeResource noPower = node(40, 0, 500);
        noPower.reserve(2, 0, 0);
        NodeResource noCapacity = new NodeResource("mdc-01", "node-1", 8, 16384, 0, 40, 30, 30, 250, 0, 50, 1000, 500);

        assertEquals(0.5, model.placementPei(noPower), 1e-9);
        assertEquals(0.5, model.placementWasteScore(noPower), 1e-9);
        assertEquals(0.5, model.placementWasteScore(noCapacity), 1e-9);
    }

    /** 정격 0이면 D = 0이라 실효 전력이 실측에 머물고 분자만 실효가 된다 — 그 혼재를 계산하지 않고 중립값을 준다(비낭비와 대칭). */
    @DisplayName("정격을 모르는 노드는 예약이 있어도 배치용 PEI 가 중립값이다")
    @Test
    void placement_pei_is_neutral_without_capacity_even_when_reserved() {
        NodeResource noCapacity = new NodeResource("mdc-01", "node-1", 8, 16384, 0, 40, 30, 30, 250, 0, 50, 1000, 500);
        noCapacity.reserve(2, 0, 0);                                 // 실효 65%, P_eff = 250 (D = 0)

        assertEquals(0.5, model.placementPei(noCapacity), 0.0);
        assertEquals(0.5, model.placementWasteScore(noCapacity), 0.0);
        assertEquals(0.32, model.normalizedPei(noCapacity), 1e-9, "진단용은 정격과 무관하게 실측 (40 / 250) / 0.5");
    }

    /** 설정이 0이면 min(1, x/0)이 사용률 &gt; 0 노드에 1.0, 사용률 0 노드에 NaN(엔진 반올림에서 0.0)을 준다 — 대체값으로 되돌린다. */
    @DisplayName("PEI 정규화 상수가 0 이하이면 두 PEI 모두 대체값 0.5 로 정규화한다")
    @Test
    void pei_normalizer_at_or_below_zero_falls_back() {
        for (double broken : new double[] {0.0, -1.0}) {
            SchedulerProperties props = new SchedulerProperties();
            props.getPower().setPeiNormalizer(broken);
            PowerModel guarded = new PowerModel(props);
            NodeResource node = node(10, 200, 500);
            NodeResource reserved = node(10, 200, 500);
            reserved.reserve(1, 0, 0);

            assertEquals(0.5, guarded.effectivePeiNormalizer(), 0.0, "대체값");
            assertEquals(0.10, guarded.normalizedPei(node), 1e-9, "0.05 / 0.5 — 1.0 이 되면 안 된다");
            assertEquals(0.1846154, guarded.placementPei(reserved), 1e-7, "22.5 / 243.75 / 0.5 — 포화도 음수도 아니다");
            assertEquals(model.placementPei(reserved), guarded.placementPei(reserved), 0.0, "기본 설정 모델과 비트 동일");
            NodeResource idle = node(0, 150, 500);
            assertEquals(0.0, guarded.normalizedPei(idle), 0.0, "사용률 0: 0/0 NaN 이 아니라 0");
            assertEquals(0.0, guarded.placementPei(idle), 0.0);
        }
        assertEquals(0.5, model.effectivePeiNormalizer(), 0.0, "기본 설정은 그대로 0.5");
    }

    /** 대체값은 0 이하에서만 — 양수 설정은 그대로 흘러야 하고, 기본값과 대체값이 같은 수라 이 한 줄이 없으면 상수 통째 대체가 살아남는다. */
    @DisplayName("PEI 정규화 상수가 양수이면 설정값이 그대로 쓰인다")
    @Test
    void positive_pei_normalizer_is_used_as_is() {
        SchedulerProperties props = new SchedulerProperties();
        props.getPower().setPeiNormalizer(3.5);
        PowerModel custom = new PowerModel(props);

        assertEquals(3.5, custom.effectivePeiNormalizer(), 0.0);
        assertEquals(0.05 / 3.5, custom.normalizedPei(node(10, 200, 500)), 1e-12);
        assertEquals(0.05 / 3.5, custom.placementPei(node(10, 200, 500)), 1e-12);
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
     * 빈 노드에 처음 얹으면 그 노드의 유휴 전력이 통째로 새로 든다.
     * 이것이 전력 통합을 임의 보너스가 아닌 물리량으로 만든다.
     */
    @DisplayName("실질 한계전력은 빈 노드에 유휴전력을 더한다")
    @Test
    void real_marginal_adds_idle_power() {
        // 8코어 중 2코어 = 0.25, 동적 범위 350W → 동적 증가분 87.5W, 유휴 150W
        assertEquals(87.5, model.realMarginalPower(node(50, 250, 500), 2), 1e-9, "가동 중");
        // node(2, 250, 500)은 유휴(2% < 5%)이고 전력이 실측(250W)이라, 유휴 역산 경로가 켜져
        // 있는 기본 모델에서는 대체값(150W)이 아니라 실측 역산값을 깨우기 비용으로 쓴다.
        // 그래서 대체값으로 계산한 237.5가 아니며, 값은 wakeupPower 역산식을 그대로 따른다.
        double reconstructedWakeup = (250.0 - 500.0 * 0.02) / (1.0 - 0.02);
        // 동적 범위의 기준선도 같은 역산 유휴다 — (500 − 244.9) × 0.25 = 63.8, 깨우기항 244.9
        assertEquals((500.0 - reconstructedWakeup) * 0.25 + reconstructedWakeup, model.realMarginalPower(node(2, 250, 500), 2), 1e-9,
                "빈 노드 — 두 자리 모두 실측 역산 유휴(대체값 237.5 도, 절편만 선언이던 332.4 도 아님)");
    }

    @DisplayName("실질 한계전력 점수는 용량 대비로 정규화한다")
    @Test
    void real_marginal_score_normalized() {
        assertEquals(1.0 - 87.5 / 500, model.marginalScore(node(50, 250, 500), 2), 1e-9);
        // real_marginal_adds_idle_power와 같은 이유로 237.5 대신 역산값 기준.
        double reconstructedWakeup = (250.0 - 500.0 * 0.02) / (1.0 - 0.02);
        assertEquals(1.0 - ((500.0 - reconstructedWakeup) * 0.25 + reconstructedWakeup) / 500, model.marginalScore(node(2, 250, 500), 2), 1e-9);
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
     * 영구 항등 — α=1이고 자르기가 걸리지 않는 노드(u_실효 + ΔU ≤ 1, 곧 C1 적격 노드)에서는 곡선 위 동적항이 선형 한계 전력과
     * 같고 <b>예약이 있어도</b> 같다. 이것이 "기본 설정에서 이 정정은 배치 결정과 비교 판의 값을 바꾸지 않는다"의 산술 근거다.
     * 기저를 되돌려도 항등이라 이 테스트는 기저 변조를 잡지 못한다 — 그 몫은 아래 볼록 테스트다.
     */
    @DisplayName("볼록 지수 1 · 자르기 비발현 노드에서는 예약 유무와 무관하게 곡선 동적항이 선형 한계전력과 같다")
    @Test
    void convex_exponent_1_is_linear_regardless_of_reservation_when_not_clamped() {
        NodeResource reserved = node(40, 250, 500);
        reserved.reserve(2, 0, 0);                                   // 실효 65%, 요구 2/8 → 90% ≤ 100%: 적격 노드의 상태
        NodeResource plain = node(40, 250, 500);

        assertEquals(model.marginalPower(reserved, 2), model.marginalPowerOnCurve(reserved, 2), 1e-9, "예약 노드도 선형과 같다");
        assertEquals(model.marginalPowerOnCurve(plain, 2), model.marginalPowerOnCurve(reserved, 2), 1e-9, "α=1 에서는 예약이 값을 바꾸지 않는다");
        assertEquals(87.5, model.marginalPowerOnCurve(reserved, 2), 1e-9);   // (500 − 500×0.3) × 2/8 = 87.5W
    }

    /**
     * 정정의 관측 가능한 효과 — α&gt;1이면 곡선 위 평가점이 실효 사용률이라 <b>예약이 있는 적격 노드</b>에서 값이 갈린다.
     * 예약 2/8인 20% 노드는 곡선 위 45% 지점에서 평가되어(45→70%) 예약 없는 노드(20→45%)보다 증가분이 크다.
     * 기저를 실측으로 되돌리면 두 값이 같아져 여기서 멈춘다.
     */
    @DisplayName("볼록 지수가 1 보다 크면 예약이 있는 적격 노드의 동적항은 실효 사용률 지점에서 평가된다")
    @Test
    void convex_exponent_evaluates_reserved_eligible_node_at_effective_usage() {
        SchedulerProperties props = new SchedulerProperties();
        props.getPower().setCurveAlpha(1.8);
        PowerModel convex = new PowerModel(props);
        NodeResource reserved = node(20, 250, 500);
        reserved.reserve(2, 0, 0);                                   // 실효 45%, 가용 8×0.8−2 = 4.4 ≥ 2: C1 적격
        NodeResource plain = node(20, 250, 500);

        double expected = (500 - convex.idlePower(reserved)) * (Math.pow(0.70, 1.8) - Math.pow(0.45, 1.8));
        assertEquals(expected, convex.marginalPowerOnCurve(reserved, 2), 1e-9, "평가점은 실효 45% 다");
        assertTrue(convex.marginalPowerOnCurve(reserved, 2) > convex.marginalPowerOnCurve(plain, 2),
                "볼록 곡선에서 더 높은 지점의 같은 폭은 더 큰 증가분이다");
        assertEquals(convex.marginalPowerOnCurve(node(45, 250, 500), 2), convex.marginalPowerOnCurve(reserved, 2), 1e-9,
                "예약 2/8 인 20% 노드와 예약 없는 45% 노드는 곡선 위 같은 자리다");
    }

    /**
     * 기본 α에서 한계 전력은 현재 부하를 보지 않아 같은 사양이면 모든 노드가 같은 값이다.
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

        // 같은 입력을 기본 α로 계산하면 셋이 모두 같다 — 그래서 구별력이 없었다
        assertEquals(model.marginalPowerOnCurve(node(50, 250, 500), 2),
                model.marginalPowerOnCurve(node(20, 250, 500), 2), 1e-9);
    }

    /**
     * 음수 사용률(계측 결함)은 0으로 자른다. α=1에서는 자르기가 없어도 차가 같아 보이지 않지만, α가 정수가 아니면 음수의 거듭제곱이
     * NaN이 되고 정수면 부호가 뒤집힌 유한 오답이 된다 — 하한 클램프가 지키는 것은 0이라는 끝값이다.
     */
    @DisplayName("음수 사용률은 0 으로 자른다 — 볼록 지수에서 NaN 이 되지 않는다")
    @Test
    void negative_usage_is_clamped_to_zero_on_the_curve() {
        SchedulerProperties props = new SchedulerProperties();
        props.getPower().setCurveAlpha(1.8);
        PowerModel convex = new PowerModel(props);

        double broken = convex.marginalPowerOnCurve(node(-5, 250, 500), 2);
        assertTrue(Double.isFinite(broken), "음수 사용률에서 NaN 이 나왔다: " + broken);
        assertEquals(convex.marginalPowerOnCurve(node(0, 250, 500), 2), broken, 1e-9, "음수는 0 과 같은 자리다");
    }

    /**
     * 깨우기 여부와 동적항이 <b>같은 기저</b>다 — 예약으로만 문턱을 넘은 노드(실측 2%, 예약 2/8)는 가동 노드로 보아 깨우기 비용을
     * 물리지 않는다. 깨우기 판정을 실측으로 되돌리면 이 노드에 깨우기 비용이 붙어 실질 한계 전력이 커진다.
     */
    @DisplayName("예약으로만 가동 문턱을 넘은 노드는 깨우기 비용을 물리지 않는다 — 깨우기 판정과 동적항이 같은 기저")
    @Test
    void a_node_active_only_by_reservation_pays_no_wakeup_power() {
        NodeResource reservedOnly = node(2, 250, 500);
        reservedOnly.reserve(2, 0, 0);                               // 실측 2% < 문턱 5% < 실효 27%
        NodeResource idle = node(2, 250, 500);

        assertTrue(model.isActive(reservedOnly));
        assertFalse(model.isActive(idle));
        assertEquals(model.marginalPowerOnCurve(reservedOnly, 2), model.realMarginalPower(reservedOnly, 2), 1e-9, "동적항만 — 깨우기 없음");
        assertTrue(model.realMarginalPower(idle, 2) > model.realMarginalPower(reservedOnly, 2), "유휴 노드는 깨우기 비용이 더 붙는다");
    }

    /**
     * 총 코어를 못 읽은 노드(0)에서 분모 가드가 없으면 요구량 0일 때 {@code 0/0 = NaN}이 점수 전체로 번진다. 요구량이 있으면
     * {@code min(1, ∞) = 1}로 가드 유무가 같아 보이므로, 이 테스트만이 가드를 잠근다.
     */
    @DisplayName("총 코어를 모르는 노드에 요구량 0 이면 동적항은 0 이다 — NaN 이 아니다")
    @Test
    void zero_total_cpu_with_zero_demand_is_zero_not_nan() {
        NodeResource unknownCores = new NodeResource("mdc-01", "node-1", 0, 16384, 0,
                40, 30, 30, 250, 500, 50, 1000, 500);

        double w = model.marginalPowerOnCurve(unknownCores, 0);
        assertTrue(Double.isFinite(w), "0 코어 노드에서 NaN 이 나왔다: " + w);
        assertEquals(0.0, w, 1e-9);
    }

    /**
     * 사용률이 100%를 넘어가지 않도록 자른다. 이 상태(가용 코어 &lt; 요구 코어)는 제약 C1이 거르므로 배치는 이르지
     * 않는다 — 여기서 잠그는 것은 식의 정의이고, 응답에서는 <b>부적격 후보의 표시값</b>으로만 나타난다.
     */
    @DisplayName("요구량이 남은 용량보다 커도 100퍼센트를 넘지 않는다 — 부적격 노드의 표시값")
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
        // 정격 500, 선언 유휴 100을 가정한 생성식 P = 100 + 400·u, u=0.02 → P=108.
        // 대체값(정격 × idleRatio=0.3=150)도, 보정 없는 실측 108도 아니고 역산한 100.0이어야 한다.
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

    /** 추정이 정확히 0인 끝값 — 0W 유휴는 물리적으로 없으므로 유효값이 아니라 대체값이어야 한다. */
    @DisplayName("역산이 정확히 0 이면 유효값이 아니라 대체값이다")
    @Test
    void a_zero_estimate_is_not_a_valid_idle_power() {
        // (10 − 500×0.02) / 0.98 = 0 정확히 — 하한 가드가 <= 0이어야 한다(< 0이면 0W 유휴가 나간다)
        assertEquals(150.0, model.wakeupPower(node(2, 10, 500)), 1e-9, "0W 유휴 전력은 없다");
    }

    /**
     * 역산 입력은 <b>실측</b> CPU다. 예약분을 섞으면 "같은 순간의 (P, u) 쌍"이 아니게 되어 식이 어긋난다.
     * 64코어 노드에 1코어를 예약하면 실효 CPU는 1% → 2.56%로 움직이지만(여전히 유휴), 실측 전력 104W는 그대로다 —
     * 실측 u로 역산하면 (104 − 500×0.01)/0.99 = 100, 실효 u로 역산하면 93.6이 나온다.
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
     * 상한 끝값 — 역산값이 정격과 정확히 같으면 여전히 유효값이어야 한다. 가드가
     * {@code estimate > pMax}가 아니라 {@code estimate >= pMax}로 바뀌면 이 끝값에서
     * 대체값(150)으로 조용히 바뀐다.
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

        // idlePower는 진단·동적 범위의 기준선이다 — 역산을 넣으면 항등식이 되어 구별력을 잃는다.
        assertEquals(150.0, reconstructing.idlePower(node), 1e-9);
        assertEquals(150.0, fixed.idlePower(node), 1e-9);
        assertEquals(fixed.proportionality(node), reconstructing.proportionality(node), 1e-9,
                "역산 여부와 무관하게 같아야 한다");
        assertEquals(fixed.wasteScore(node), reconstructing.wasteScore(node), 1e-9,
                "역산 여부와 무관하게 같아야 한다");
    }

    /**
     * 유휴 기준선 일원화 — 정수 픽스처(8코어 · 2코어 요구 · 정격 500 · 유휴 108W 계측·CPU 2%):
     * 역산 유휴 (108 − 500×0.02)/0.98 = 100.0, 동적 (500 − 100) × 0.25 = 100.0 → 합 200.0.
     * 정정을 되돌리면(절편만 선언 유휴 150) 87.5 + 100 = 187.5, 반대 방향(깨우기항을 선언 유휴로)이면 요구 0 코어에서 150.
     */
    @DisplayName("계측된 유휴 노드는 동적 범위 기준선과 깨우기항이 같은 역산 유휴다")
    @Test
    void measured_idle_node_uses_one_idle_baseline_in_both_places() {
        NodeResource node = node(2, 108, 500);

        assertEquals(200.0, model.realMarginalPower(node, 2), 1e-9, "동적 100 + 깨우기 100 — 절편만 선언이면 187.5");
        assertEquals(100.0, model.realMarginalPower(node, 0), 1e-9, "요구 0 이면 깨우기항 단독 — 선언 유휴였다면 150");
        double wakeup = model.wakeupPower(node);
        double dynamicBase = 500.0 - (model.realMarginalPower(node, 2) - wakeup) / 0.25;
        assertEquals(wakeup, dynamicBase, 1e-9, "동적항의 절편을 역산하면 깨우기항과 같은 값이 나온다");
        assertEquals(87.5, model.marginalPowerOnCurve(node, 2), 1e-9, "공개 곡선 함수는 선언 절편 그대로 — 진단·선형 식이 쓴다");
    }

    @DisplayName("미계측·가동·역산 범위 밖·역산 끔 노드는 비트 단위로 이전과 같다")
    @Test
    void nodes_without_a_measured_idle_are_bit_identical_to_before() {
        assertEquals(87.5 + 150.0, model.realMarginalPower(node(2, 0, 500), 2), 0.0, "미계측(전력 0) — 두 자리 모두 선언 유휴 150");
        assertEquals(87.5, model.realMarginalPower(node(50, 250, 500), 2), 0.0, "가동 노드 — 동적항만, 절편 선언");
        assertEquals(87.5 + 150.0, model.realMarginalPower(node(2, 5, 500), 2), 0.0, "역산이 음수 — 대체값으로 두 자리 모두 선언 유휴");
        assertEquals(87.5 + 150.0, model.realMarginalPower(node(4, 520, 500), 2), 0.0, "역산이 정격 초과 — 대체값으로 두 자리 모두 선언 유휴");
        SchedulerProperties off = new SchedulerProperties();
        off.getPower().setIdleFromMeasurement(false);
        assertEquals(87.5 + 150.0, new PowerModel(off).realMarginalPower(node(2, 108, 500), 2), 0.0,
                "역산을 끄면 두 자리 모두 선언 유휴 150 — 정정 전 power-idle-fixed 열(87.5 + 150)과 같은 식이라 그 열의 값은 비트 불변");
    }

    @DisplayName("실질 한계전력은 역산한 유휴전력으로 깨어난다")
    @Test
    void real_marginal_power_wakes_with_the_measured_idle() {
        // 공개 marginalOnCurve(α=1) = 350 × (2/8) = 87.5 — 선언 유휴 절편 그대로(진단·선형 식이 쓴다).
        // 한계 전력 식은 역산 유휴 100을 두 자리에 쓴다: 동적 (500−100)×0.25 = 100 + 깨우기 100 = 200.0
        // (대체값 시절 237.5 · 절편만 선언이던 187.5 아님).
        NodeResource node = node(2, 108, 500);

        assertEquals(87.5, model.marginalPowerOnCurve(node, 2), 1e-9);
        assertEquals(200.0, model.realMarginalPower(node, 2), 1e-9);
    }

    // ── 배치 점수용 PEI의 코어 비율 ────────────────────────────

    /**
     * 코어 수가 모두 같은 후보에서는 정정 전 식과 비트 단위로 같다. 코어 {3, 12, 48, 49}를 고른 이유: c = 3·12·48에서는
     * {@code (u·c)/c ≠ u}인 사용률이 있어 곱 순서를 바꾸면 여기서 멈추고, c = 49는 {@code c × (1/c) ≠ 1}이라 역수를 곱하면
     * 여기서 멈춘다. 예약 0의 기준은 손대지 않는 진단 {@code normalizedPei}, 예약 1의 기준은 공개 메서드로 다시 적은 정정 전 식이다.
     * 코어 0 노드는 평균에서 빠지므로 후보에 섞어 둔다.
     */
    @DisplayName("코어 수가 모두 같은 후보에서 배치용 PEI 는 정정 전 식과 비트 단위로 같다")
    @Test
    void placement_pei_is_bit_identical_to_the_previous_formula_on_homogeneous_candidates() {
        List<String> mismatches = new ArrayList<>();
        for (int cores : new int[] {3, 12, 48, 49}) {
            for (int tenths = 1; tenths <= 999; tenths++) {
                double usage = tenths / 10.0;
                for (int reserved = 0; reserved <= 1; reserved++) {
                    NodeResource target = nodeWithCores(cores, usage, 250, 500);
                    target.reserve(reserved, 0, 0);
                    List<NodeResource> candidates = List.of(target, nodeWithCores(cores, 40, 250, 500),
                            nodeWithCores(cores, 70, 300, 500), nodeWithCores(0, 40, 250, 500));
                    double expected = reserved == 0
                            ? model.normalizedPei(target)
                            : Math.min(1.0, (target.effectiveCpuUsagePercent() / model.effectivePowerWatts(target))
                                    / model.effectivePeiNormalizer());
                    double actual = model.placementPei(target, PowerModel.meanPositiveCores(candidates));
                    if (Double.doubleToRawLongBits(actual) != Double.doubleToRawLongBits(expected)
                            || Double.doubleToRawLongBits(model.placementPei(target)) != Double.doubleToRawLongBits(expected)) {
                        mismatches.add(cores + "코어 사용률 " + usage + " 예약 " + reserved + ": " + actual + " ≠ " + expected);
                    }
                }
            }
        }
        assertEquals(List.of(), mismatches.subList(0, Math.min(5, mismatches.size())), "어긋난 점 " + mismatches.size() + "개");
    }

    /** 코어 8·16·24(평균 16)의 비율은 0.5 · 1 · 1.5이고, 비율을 곱한 값도 1.0에서 포화한다. */
    @DisplayName("코어 수가 섞인 후보의 배치용 PEI 는 실효 사용률에 코어 비율을 곱한 값이고 1.0 에서 포화한다")
    @Test
    void placement_pei_multiplies_the_core_ratio_and_still_saturates() {
        NodeResource small = nodeWithCores(8, 40, 250, 500);
        NodeResource middle = nodeWithCores(16, 40, 250, 500);
        NodeResource large = nodeWithCores(24, 40, 250, 500);
        double mean = PowerModel.meanPositiveCores(List.of(small, middle, large));

        assertEquals(16.0, mean, 0.0);
        assertEquals(0.5, PowerModel.coreRatio(small, mean), 0.0);
        assertEquals(1.0, PowerModel.coreRatio(middle, mean), 0.0);
        assertEquals(1.5, PowerModel.coreRatio(large, mean), 0.0);
        // 40 / 250 / 0.5 = 0.32에 비율을 곱한다
        assertEquals(0.16, model.placementPei(small, mean), 1e-12);
        assertEquals(0.32, model.placementPei(middle, mean), 1e-12);
        assertEquals(0.48, model.placementPei(large, mean), 1e-12);

        NodeResource frugalSmall = nodeWithCores(8, 90, 100, 500);    // 90 / 100 / 0.5 = 1.8 → 비율 0.5를 곱해 0.9
        NodeResource frugalLarge = nodeWithCores(24, 90, 100, 500);   // 1.8 × 1.5 = 2.7 → 상한 1.0
        assertEquals(0.9, model.placementPei(frugalSmall, mean), 1e-12);
        assertEquals(1.0, model.placementPei(frugalLarge, mean), 0.0);
    }

    /** 코어 수 0 이하 노드는 평균에서 빠지고 비율 1이다. 모든 후보가 0 이하이면 평균 0이고 모두 비율 1이다. */
    @DisplayName("코어 수가 0 이하인 노드는 평균에서 빠지고 코어 비율이 1 이다")
    @Test
    void nodes_without_cores_are_left_out_of_the_mean_and_get_ratio_one() {
        NodeResource zero = nodeWithCores(0, 40, 250, 500);
        NodeResource negative = nodeWithCores(-1, 40, 250, 500);
        NodeResource small = nodeWithCores(8, 40, 250, 500);
        double mean = PowerModel.meanPositiveCores(List.of(small, nodeWithCores(24, 40, 250, 500), zero, negative));

        assertEquals(16.0, mean, 0.0, "(8 + 24) / 2 — 코어 0 이하 노드는 세지 않는다");
        assertEquals(1.0, PowerModel.coreRatio(zero, mean), 0.0);
        assertEquals(1.0, PowerModel.coreRatio(negative, mean), 0.0);
        assertEquals(model.placementPei(zero), model.placementPei(zero, mean), 0.0, "비율 1 — 정정 전 값");
        assertEquals(0.32, model.placementPei(zero, mean), 1e-12);

        double none = PowerModel.meanPositiveCores(List.of(zero, negative));
        assertEquals(0.0, none, 0.0, "코어 수가 0 보다 큰 노드가 없으면 평균 0");
        assertEquals(0.0, PowerModel.meanPositiveCores(List.of()), 0.0);
        assertEquals(1.0, PowerModel.coreRatio(small, none), 0.0, "평균 0 이면 비율 1");
        assertEquals(model.placementPei(small), model.placementPei(small, none), 0.0);
    }

    private NodeResource node(double cpuUsage, double powerWatts, double capacityWatts) {
        return new NodeResource("mdc-01", "node-1", 8, 16384, 0,
                cpuUsage, 30, 30, powerWatts, capacityWatts, 50, 1000, 500);
    }

    private NodeResource nodeWithCores(int cores, double cpuUsage, double powerWatts, double capacityWatts) {
        return new NodeResource("mdc-01", "node-" + cores, cores, 16384, 0,
                cpuUsage, 30, 30, powerWatts, capacityWatts, 50, 1000, 500);
    }
}

package com.innogrid.aims.scheduler.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.NetworkModel;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 전력 엔진 점수식이 고치려 한 결함들이 실제로 사라졌는지 확인한다.
 *
 * <p>각 테스트는 재작성 전 코드에서 <b>실패했을</b> 상황을 재현한다. 값은 손으로 검산할 수 있게
 * 잡았다 — 용량 500W, 유휴 비율 0.3(P_idle 150W), 동적 범위 350W, 8코어, 2코어 워크로드이면
 * 동적 증가분이 87.5W다.</p>
 */
class PowerAwareScoringTest {

    private final PowerAwareScheduler scheduler = new PowerAwareScheduler();

    /**
     * 예약이 쌓이면 엔진이 내는 전력 복합 점수가 움직인다 — 후보 상세의 PEI 칸이 배치용 실효 PEI와 같고, 비낭비 칸은 항등,
     * 전력점수의 이동은 {@code peiWeight × ΔPEI}다(한계 전력 동적항은 α=1에서 불변). 엔진 배선을 진단용 두 함수로 되돌리면
     * PEI 칸이 예약 전과 같아져 여기서 멈춘다.
     */
    @DisplayName("예약이 쌓인 노드의 후보 상세 PEI 는 배치용 실효 PEI 이고 전력점수 상승분은 peiWeight × ΔPEI 다")
    @Test
    void reserved_node_power_score_moves_by_pei_weight_times_delta() {
        SchedulerProperties props = new SchedulerProperties();
        PowerModel model = new PowerModel(props);
        NodeResource node = node("n", 45, 40, 387, 55);
        List<NodeResource> nodes = List.of(node);

        SchedulingResult before = scheduler.schedule(workload(1), nodes, policy());
        node.reserve(1, 0, 0);
        SchedulingResult after = scheduler.schedule(workload(1), nodes, policy());

        double dPei = model.placementPei(node) - model.normalizedPei(node);
        assertTrue(dPei > 0, "판정 판 대표 노드는 예약이 쌓이면 PEI 상승");
        assertEquals(round4(model.normalizedPei(node)), detail(before, "n", "PEI"), 0.0, "예약 0: 상세 PEI = 실측 PEI");
        assertEquals(round4(model.placementPei(node)), detail(after, "n", "PEI"), 0.0, "예약 후: 상세 PEI = 배치용 실효 PEI");
        assertTrue(detail(after, "n", "PEI") > detail(before, "n", "PEI"), "상세 PEI 가 예약을 따라 움직인다");
        assertEquals(detail(before, "n", "비낭비"), detail(after, "n", "비낭비"), 0.0, "비낭비 항등(적격 노드)");
        assertEquals(detail(before, "n", "실질한계전력점수"), detail(after, "n", "실질한계전력점수"), 0.0, "α=1 · 자르기 비발현이라 한계항 불변");
        assertEquals(props.getPower().getPeiWeight() * dPei,
                detail(after, "n", "전력점수") - detail(before, "n", "전력점수"), 1.01e-4,
                "전력점수의 이동은 PEI 항 하나에서 온다(4자리 반올림 두 번의 상한 1e-4 에 1% 여유)");
    }

    /**
     * 비낭비 배선을 잡는 유일한 자리 — 적격 노드에서는 두 함수가 항등이라, 실효 사용률이 100에서 잘리는 부적격 후보에서만 갈린다.
     * u=90 · P=440 · 예약 2/8: P_eff 527.5, 실효 이상 곡선 500 → overdraw 27.5 → 0.945. 진단용은 P_ideal 465 → 1.0.
     */
    @DisplayName("실효 사용률이 잘린 부적격 후보의 상세 비낭비 칸은 배치용 실효값 0.945 다")
    @Test
    void clamped_ineligible_candidate_reports_placement_waste() {
        NodeResource clamped = node("c", 90, 40, 440, 55);
        clamped.reserve(2, 0, 0);
        SchedulingResult result = scheduler.schedule(workload(1), List.of(clamped), policy());

        assertEquals(0.945, detail(result, "c", "비낭비"), 0.0, "설정 기본값(유휴비 0.3 · 정격 500) 전제 — 기본값이 바뀌면 리터럴도 함께 고친다");
        assertEquals(1.0, new PowerModel(new SchedulerProperties()).wasteScore(clamped), 1e-9, "진단용은 예약을 보지 않아 1.0");
    }

    /** 설정 오류(정규화 상수 0)가 엔진까지 번지지 않는다 — 옛 코드는 상세 PEI 1.0 포화·전력점수 과대였다. */
    @DisplayName("PEI 정규화 상수가 0 인 설정에서도 후보 상세 PEI 는 대체값으로 정규화된 값이다")
    @Test
    void zero_pei_normalizer_setting_does_not_saturate_engine_pei() {
        SchedulerProperties broken = new SchedulerProperties();
        broken.getPower().setPeiNormalizer(0);
        PowerAwareScheduler guarded = new PowerAwareScheduler(new PowerModel(broken), new ThermalModel(broken), new NetworkModel(broken), broken);
        NodeResource node = node("n", 10, 40, 200, 55);

        SchedulingResult result = guarded.schedule(workload(1), List.of(node), policy());
        assertEquals(0.10, detail(result, "n", "PEI"), 0.0, "(10 / 200) / 0.5 — 1.0 포화가 아니다");
        assertEquals(detail(scheduler.schedule(workload(1), List.of(node("n", 10, 40, 200, 55)), policy()), "n", "전력점수"),
                detail(result, "n", "전력점수"), 0.0, "기본 설정 엔진과 같은 전력점수");
    }

    // ── 열 항의 실효 온도 기저 ─────────────────────────────────

    private static double thermalScoreOf(ThermalModel model, double temperature, SchedulingPolicy policy) {
        double warn = policy.getWarningTemperatureCelsius() != null ? policy.getWarningTemperatureCelsius() : model.defaultWarningCelsius();
        double cooling = Math.max(0.0, Math.min(1.0, model.coolingScore(temperature, policy.getMaxTemperatureCelsius())));
        return cooling * (1.0 - model.warningPenalty(temperature, warn, policy.getMaxTemperatureCelsius()));
    }

    /** 예약 0이면 실효 온도 = 실측 온도라 열점수가 실측 식과 비트 단위로 같다 — 첫 배치는 불변. */
    @DisplayName("예약이 없으면 열점수는 실측 온도 식과 비트 단위로 같다")
    @Test
    void thermal_score_without_reservation_equals_measured_formula() {
        SchedulerProperties props = new SchedulerProperties();
        ThermalModel model = new ThermalModel(props);
        for (double temp : new double[] {35.6, 52.5, 68.5}) {
            NodeResource node = node("n", 45, 40, 387, temp);
            SchedulingResult result = scheduler.schedule(workload(1), List.of(node), policy());
            assertEquals(round4(thermalScoreOf(model, temp, policy())), detail(result, "n", "열점수"), 0.0, "T=" + temp);
        }
        // 손검산 리터럴 하나(임계 75 · 경고 65): 52.5℃ → (1 − (52.5/75)²) × 1 = 0.51 — 헬퍼가 식을 재구현하므로 절대값 한 점은 리터럴로 고정한다
        assertEquals(0.51, detail(scheduler.schedule(workload(1), List.of(node("n", 45, 40, 387, 52.5)), policy()), "n", "열점수"), 0.0);
    }

    /**
     * 예약이 쌓이면 열점수가 실효 온도 T(1 + k·r)의 식으로 내려간다 — 냉각·경고 감점 둘 다 실효 온도를 읽는다.
     * 정정 전(실측 고정)이면 예약 뒤에도 값이 같아 여기서 실패한다.
     */
    @DisplayName("예약이 쌓인 노드의 열점수는 실효 온도 식으로 내려간다")
    @Test
    void thermal_score_follows_effective_temperature_when_reserved() {
        SchedulerProperties props = new SchedulerProperties();
        ThermalModel model = new ThermalModel(props);
        NodeResource node = node("n", 45, 40, 387, 52.5);
        double before = detail(scheduler.schedule(workload(1), List.of(node), policy()), "n", "열점수");
        node.reserve(1, 0, 0);                                        // r = 1/8 → T_eff = 52.5 × (1 + 0.15 × 0.125) = 53.484375
        double after = detail(scheduler.schedule(workload(1), List.of(node), policy()), "n", "열점수");

        assertEquals(53.484375, model.effectiveTemperature(node), 1e-9);
        assertEquals(round4(thermalScoreOf(model, 53.484375, policy())), after, 0.0, "실효 온도 식");
        assertEquals(0.4915, after, 0.0, "손검산: 1 − (53.484375/75)² = 0.4915");
        assertTrue(after < before, "예약이 쌓이면 열점수는 내려간다(감점)");
    }

    /** 실효 온도가 경고 문턱을 넘기면 감점이 생긴다 — 냉각만 실효로 바꾼 구현은 여기서 갈린다. */
    @DisplayName("실효 온도가 경고 문턱을 넘기면 경고 감점도 실효 온도를 따른다")
    @Test
    void warning_penalty_uses_effective_temperature() {
        SchedulerProperties props = new SchedulerProperties();
        ThermalModel model = new ThermalModel(props);
        NodeResource node = node("n", 40, 40, 300, 64.0);           // 실측은 경고(65℃) 아래
        node.reserve(2, 0, 0);                                        // r = 0.25 → T_eff = 64 × 1.0375 = 66.4 > 65
        SchedulingResult result = scheduler.schedule(workload(1), List.of(node), policy());
        double warn = model.defaultWarningCelsius();

        assertEquals(66.4, model.effectiveTemperature(node), 1e-9);
        assertTrue(model.warningPenalty(66.4, warn, policy().getMaxTemperatureCelsius()) > 0, "실효 온도는 경고 구간");
        assertEquals(0.0, model.warningPenalty(64.0, warn, policy().getMaxTemperatureCelsius()), 0.0, "실측 온도는 경고 아래");
        assertEquals(round4(thermalScoreOf(model, 66.4, policy())), detail(result, "n", "열점수"), 0.0, "감점까지 실효 온도로");
        assertEquals(0.1859, detail(result, "n", "열점수"), 0.0, "손검산: (1 − (66.4/75)²) × (1 − 0.14) = 0.1859");
        assertTrue(detail(result, "n", "열점수") < round4(Math.min(1.0, model.coolingScore(66.4, policy().getMaxTemperatureCelsius()))), "냉각만 실효이면 감점이 빠진다");
    }

    /** 온도를 수집하지 못한 노드는 예약이 있어도 열점수 중립 — 실효 온도도 0을 유지하고 게이트는 실측을 본다. */
    @DisplayName("온도 미계측 노드는 예약이 있어도 열점수가 중립값이다")
    @Test
    void unmeasured_temperature_stays_neutral_when_reserved() {
        NodeResource node = node("n", 40, 40, 300, 0);
        node.reserve(2, 0, 0);
        SchedulingResult result = scheduler.schedule(workload(1), List.of(node), policy());
        assertEquals(0.5, detail(result, "n", "열점수"), 0.0);
        assertEquals(0.0, detail(result, "n", "온도계측"), 0.0);
    }

    /**
     * 예약 뒤 총점 이동은 열 항과 전력 PEI 항에서만 온다 — 열점수 이동 × 0.9 × 0.15/0.35가 열 항 몫이고, 전력 3항 칸과 배치 후 예상
     * 온도 칸은 예약 전과 같은 식(이미 실효 기저)이다.
     */
    @DisplayName("예약 뒤 총점 이동은 열 항·전력 항·자원 항 몫으로 분해된다")
    @Test
    void total_score_shift_from_thermal_term_matches_policy_weight() {
        SchedulerProperties props = new SchedulerProperties();
        PowerModel powerModel = new PowerModel(props);
        ThermalModel thermalModel = new ThermalModel(props);
        NodeResource node = node("n", 45, 40, 387, 52.5);
        SchedulingResult before = scheduler.schedule(workload(1), List.of(node), policy());
        node.reserve(1, 0, 0);
        SchedulingResult after = scheduler.schedule(workload(1), List.of(node), policy());

        // 열 항 이동은 상세 칸이 아니라 실효 온도 식으로 독립 산출한다 — 상세 칸에서 읽으면 실측 복귀 변이에서도 양변이 함께 0이 된다
        double dThermal = thermalScoreOf(thermalModel, 53.484375, policy()) - thermalScoreOf(thermalModel, 52.5, policy());
        double dPei = powerModel.placementPei(node) - powerModel.normalizedPei(node);           // 전력 항 이동은 PEI 항 하나(비낭비 항등·α=1 한계항 불변)
        double dPower = props.getPower().getPeiWeight() * dPei;
        double thermalShare = (1.0 - props.getPower().getResourceShare()) * 0.15 / 0.35;
        double powerShare = (1.0 - props.getPower().getResourceShare()) * 0.20 / 0.35;
        double resourceShift = props.getPower().getResourceShare() * 0.5 * (-0.125);   // cpu 여유만 1/8 줄고 메모리는 예약 0
        double expected = thermalShare * dThermal + powerShare * dPower + resourceShift;
        double actual = after.candidateScores().get(0).totalScore() - before.candidateScores().get(0).totalScore();
        assertTrue(dThermal < 0, "열 항 감점");
        // 오차 상한: 총점 4자리 반올림 두 번(1e-4) + 상세 칸을 거치지 않으므로 그 외 반올림 없음 → 2e-4는 두 배 여유
        assertEquals(expected, actual, 2e-4, "총점 이동 = 열 항 몫 + 전력 항 몫 + 자원 항 몫");
        assertEquals(round4(powerModel.placementPei(node)), detail(after, "n", "PEI"), 0.0, "전력 PEI 칸은 자기 실효 식 그대로");
        assertEquals(round4(thermalModel.predictedTemperature(node, 1)), detail(after, "n", "배치후예상온도C"), 0.0, "예상 온도 칸은 이미 실효 기저");
    }

    /** 실효 온도가 임계(75℃)를 넘으면 냉각 항이 0으로 잘려 열점수 0 — 음수가 아니다. 온도 인지를 끈 정책에서는 제약이 막지 않아 이 구역에 닿을 수 있다. */
    @DisplayName("실효 온도가 임계를 넘는 후보의 열점수는 0 으로 포화하고 음수가 되지 않는다")
    @Test
    void thermal_score_saturates_at_zero_above_max_temperature() {
        NodeResource node = node("n", 40, 40, 300, 74.0);
        node.reserve(4, 0, 0);                                        // r = 0.5 → T_eff = 74 × 1.075 = 79.55 > 75
        SchedulingResult result = scheduler.schedule(workload(1), List.of(node), policy());
        assertEquals(0.0, detail(result, "n", "열점수"), 0.0);
        assertTrue(result.candidateScores().get(0).totalScore() >= 0.0, "총점은 음수가 되지 않는다");
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ── 결함 A — 비례성이 저전력 노드를 벌하던 문제 ────────────────

    /**
     * 이상 곡선(360W)보다 적게 쓰는 200W 노드가, 넘겨 쓰는 440W 노드보다 높아야 한다.
     * 옛 수식은 대칭 편차를 써서 200W를 0.68, 440W를 0.84로 매겼다.
     */
    @DisplayName("전력을 적게 쓰는 노드를 벌하지 않는다")
    @Test
    void low_power_node_not_penalized() {
        List<NodeResource> nodes = List.of(
                node("frugal", 60, 50, 200, 55),
                node("overdraw", 60, 50, 440, 55));

        SchedulingResult result = scheduler.schedule(workload(2), nodes, policy());

        assertEquals("frugal", result.selectedNodeId());
        assertTrue(detail(result, "frugal", "비낭비") > detail(result, "overdraw", "비낭비"));
    }

    // ── 결함 B·C·M3 — 한계 전력이 결정에 반영되고 노드를 구별한다 ──

    /**
     * M3 — 빈 노드에 처음 얹으면 그 노드의 유휴 전력이 새로 든다.
     * 옛 수식은 둘 다 87.5W로 봐서 통합할 이유를 설명하지 못했다.
     */
    @DisplayName("빈 노드에는 유휴 기동분이 더해진다")
    @Test
    void idle_node_adds_startup_power() {
        List<NodeResource> nodes = List.of(
                node("running", 50, 50, 250, 55),
                node("parked", 2, 50, 250, 55));

        SchedulingResult result = scheduler.schedule(workload(2), nodes, policy());

        assertEquals(87.5, detail(result, "running", "실질한계전력W"), 1e-4);
        // parked는 계측(250W)·유휴(2%)라 깨우기 비용을 실측에서 역산한다: (250 − 500×0.02)/(1−0.02) = 244.9W.
        // 대체값 150W(정격×0.3)가 아니다 — 이 픽스처는 놀면서 250W를 쓰는 노드라 깨우는 값도 그만큼이다.
        double recon = (250 - 500 * 0.02) / 0.98;
        assertEquals((500 - recon) * 0.25 + recon, detail(result, "parked", "실질한계전력W"), 1e-4,
                "실측에서 역산한 유휴 244.9W 가 깨우기항이고 동적 범위의 기준선이기도 하다");
        assertEquals(1.0, detail(result, "running", "가동중"), 1e-9);
        assertEquals(0.0, detail(result, "parked", "가동중"), 1e-9);
    }

    /** 결함 C — 볼록 지수를 올리면 같은 사양 노드도 현재 부하에 따라 갈린다. */
    @DisplayName("볼록 지수를 올리면 부하가 높은 노드의 한계전력이 커진다")
    @Test
    void convex_raises_marginal_power() {
        SchedulerProperties props = new SchedulerProperties();
        props.getPower().setCurveAlpha(1.8);
        PowerAwareScheduler convex = new PowerAwareScheduler(props);

        List<NodeResource> nodes = List.of(
                node("busy", 70, 50, 250, 55),
                node("light", 20, 50, 250, 55));

        SchedulingResult result = convex.schedule(workload(2), nodes, policy());

        assertTrue(detail(result, "busy", "실질한계전력W") > detail(result, "light", "실질한계전력W"),
                "바쁜 노드에 얹으면 전력이 더 든다");
    }

    // ── 결함 D — 워크로드 크기가 결정에 영향을 준다 ────────────────

    /** 옛 수식은 워크로드를 표시용으로만 써서 1코어든 6코어든 순위가 같았다. */
    @DisplayName("워크로드 크기가 점수를 움직인다")
    @Test
    void workload_size_moves_score() {
        List<NodeResource> nodes = List.of(node("n1", 40, 40, 250, 55));

        double small = scheduler.schedule(workload(1), nodes, policy()).finalScore();
        double large = scheduler.schedule(workload(4), nodes, policy()).finalScore();

        assertTrue(small > large, "많이 요구할수록 전력이 더 늘어 점수가 낮아야 한다: " + small + " vs " + large);
    }

    // ── 결함 E — 미수집 노드를 우대하지 않는다 ────────────────────

    /**
     * 전력·온도가 0이면 판단 근거가 없다. 0 점을 주면 최악으로, 감점을 안 하면 최상으로
     * 평가된다. 둘 다 틀렸으므로 중립 0.5를 준다.
     */
    @DisplayName("전력을 수집하지 못하면 중립값을 쓴다")
    @Test
    void unmeasured_power_neutral() {
        List<NodeResource> nodes = List.of(node("no-ipmi", 40, 40, 0, 0));

        SchedulingResult result = scheduler.schedule(workload(2), nodes, policy());

        assertEquals(0.5, detail(result, "no-ipmi", "전력점수"), 1e-9);
        assertEquals(0.5, detail(result, "no-ipmi", "열점수"), 1e-9);
    }

    /** 전력 메트릭이 하나도 없으면 근거 문장이 그 사실을 밝혀야 한다. */
    @DisplayName("전력 메트릭이 없으면 근거에 밝힌다")
    @Test
    void missing_power_metric_in_details() {
        List<NodeResource> nodes = List.of(
                node("a", 20, 30, 0, 0),
                node("b", 60, 70, 0, 0));

        SchedulingResult result = scheduler.schedule(workload(2), nodes, policy());

        assertTrue(result.reason().contains("전력 메트릭이 없어"), result.reason());
        assertEquals("a", result.selectedNodeId(), "이때는 자원 여유로 결정된다");
    }

    /** 계측된 노드가 하나라도 있으면 클러스터 요약을 근거에 담는다. */
    @DisplayName("계측된 노드가 있으면 클러스터 요약을 남긴다")
    @Test
    void measured_leaves_cluster_summary() {
        List<NodeResource> nodes = List.of(
                node("measured", 40, 40, 250, 50),
                node("parked", 1, 10, 150, 40));

        SchedulingResult result = scheduler.schedule(workload(2), nodes, policy());

        assertTrue(result.reason().contains("클러스터"), result.reason());
        assertTrue(result.reason().contains("반납가능"), result.reason());
    }

    // ── 결함 G — 점수가 CPU에 대해 단조롭다 ──────────────────────

    /**
     * 옛 수식은 PEI(증가)와 CPU 여유(감소)가 맞붙어 30% 부근에서 꺾였다. 꺾이면 "왜 이 노드가
     * 뽑혔는지" 설명할 수 없다. 방향이 증가인 것은 통합 목적상 의도된 것이다 — 같은 전력에서
     * CPU가 높으면 단위 전력당 처리량이 좋다. 몰림은 사용률 임계가 막는다.
     */
    @DisplayName("점수는 CPU 사용률에 대해 단조롭다")
    @Test
    void score_monotonic_in_cpu() {
        List<Double> series = new ArrayList<>();
        for (int cpu = 10; cpu <= 70; cpu += 5) {
            List<NodeResource> nodes = List.of(node("n", cpu, 50, 250, 55));
            series.add(scheduler.schedule(workload(2), nodes, policy()).finalScore());
        }

        int flips = 0;
        for (int i = 2; i < series.size(); i++) {
            double prev = series.get(i - 1) - series.get(i - 2);
            double curr = series.get(i) - series.get(i - 1);
            if (prev * curr < 0) flips++;
        }

        assertEquals(0, flips, "증감 방향이 바뀌면 안 된다: " + series);
    }

    // ── 결함 H — 정책 가중치와 필드 사용 ──────────────────────────

    /** 정책의 전력:온도 비율이 실제로 반영된다. 옛 코드는 정책 가중치를 아예 보지 않았다. */
    @DisplayName("정책의 전력 온도 비율이 반영된다")
    @Test
    void policy_power_temp_ratio_applied() {
        List<NodeResource> nodes = List.of(
                node("power-good", 60, 50, 200, 70),   // 전력 효율 좋고 뜨겁다
                node("cool-poor", 20, 50, 400, 35));   // 시원하지만 전력 낭비

        SchedulingPolicy powerFirst = policy();
        powerFirst.setPowerWeight(0.90);
        powerFirst.setTemperatureWeight(0.05);

        SchedulingPolicy thermalFirst = policy();
        thermalFirst.setPowerWeight(0.05);
        thermalFirst.setTemperatureWeight(0.90);

        assertEquals("power-good", scheduler.schedule(workload(2), nodes, powerFirst).selectedNodeId());
        assertEquals("cool-poor", scheduler.schedule(workload(2), nodes, thermalFirst).selectedNodeId());
    }

    /**
     * `networkScore` 칸에는 실측 네트워크 점수를 담는다.
     * 0을 넣으면 "해당 없음"이 아니라 "네트워크 포화"로 읽히고, weighted 엔진이 같은 칸에
     * 담는 값과도 의미가 어긋난다.
     */
    @DisplayName("networkScore에 실측 네트워크 점수가 담긴다")
    @Test
    void networkscore_carries_measured() {
        List<NodeResource> nodes = List.of(node("n1", 40, 40, 250, 55));

        NodeScore power = scheduler.schedule(workload(2), nodes, policy()).candidateScores().get(0);
        NodeScore weighted = new WeightedScoreScheduler()
                .schedule(workload(2), nodes, policy()).candidateScores().get(0);

        assertEquals(weighted.networkScore(), power.networkScore(), 1e-9,
                "두 엔진이 같은 칸에 같은 의미의 값을 담아야 한다");
        assertNotEquals(0.0, power.networkScore(), "0 은 포화로 오해된다");
    }

    /** 점수에 쓰지 않는 값도 판단 근거로는 남긴다. */
    @DisplayName("점수에 쓰지 않는 값도 details에 남긴다")
    @Test
    void unused_values_kept_in_details() {
        List<NodeResource> nodes = List.of(node("n1", 40, 40, 250, 55));

        NodeScore top = scheduler.schedule(workload(2), nodes, policy()).candidateScores().get(0);

        assertTrue(top.details().containsKey("네트워크한산도"));
        assertTrue(top.details().containsKey("가동중"));
        assertTrue(top.details().containsKey("실질한계전력W"));
    }

    // ── 계측되지 않은 값의 표시 ──────────────────────────────────

    /**
     * 계측하지 못한 값에서 파생된 항목은 숫자를 채우지 않는다.
     * 0이나 0.5를 넣으면 "측정했더니 그 값"으로 읽혀 판단 근거가 없다는 사실이 가려진다.
     */
    @DisplayName("계측되지 않으면 파생 항목을 null로 둔다")
    @Test
    void unmeasured_derived_fields_null() {
        List<NodeResource> nodes = List.of(node("no-ipmi", 40, 40, 0, 0));

        NodeScore s = scheduler.schedule(workload(2), nodes, policy()).candidateScores().get(0);

        assertEquals(0.0, s.details().get("전력계측"), 1e-9);
        assertEquals(0.0, s.details().get("온도계측"), 1e-9);
        assertNull(s.details().get("PEI"), "실측 전력으로 나누는 값이라 낼 수 없다");
        assertNull(s.details().get("비낭비"), "이상 곡선과 비교할 실측값이 없다");
        assertNull(s.details().get("배치후예상전력W"), "기준 전력을 모르면 절대값을 낼 수 없다");
        assertNull(s.details().get("배치후예상온도C"), "0℃ 로 오해된다");

        // 키는 그대로 있어야 한다 — 빠지면 노드마다 항목 구성이 달라진다
        for (String key : List.of("PEI", "비낭비", "배치후예상전력W", "배치후예상온도C")) {
            assertTrue(s.details().containsKey(key), key + " 키는 남아 있어야 한다");
        }
    }

    @DisplayName("정책 온도 임계가 0이어도 실측 온도는 계측값으로 표시한다")
    @Test
    void measured_temperature_does_not_depend_on_policy_threshold() {
        SchedulingPolicy policy = policy();
        policy.setMaxTemperatureCelsius(0);

        NodeScore score = scheduler.schedule(workload(2),
                List.of(node("measured", 40, 40, 250, 55)), policy).candidateScores().get(0);

        assertEquals(1.0, score.details().get("온도계측"), 1e-9);
        assertNotNull(score.details().get("배치후예상온도C"));
    }

    /**
     * 가동 노드의 동적 증가분은 용량과 CPU 사용률만으로 구하므로 계측이 없어도 성립한다.
     * 미계측 노드에서는 유휴 여부를 판단할 근거가 없어 깨우기 비용을 더하지 않는다.
     * 낼 수 있는 값까지 비우면 정보를 버리는 것이다.
     */
    @DisplayName("계측이 없어도 한계 전력 증분은 낸다")
    @Test
    void marginal_power_without_measurement() {
        List<NodeResource> nodes = List.of(node("no-ipmi", 40, 40, 0, 0));

        NodeScore s = scheduler.schedule(workload(2), nodes, policy()).candidateScores().get(0);

        assertNotNull(s.details().get("실질한계전력W"));
        assertEquals(87.5, s.details().get("실질한계전력W"), 1e-4, "용량 500W · 8코어 중 2코어");
        assertNotNull(s.details().get("실질한계전력점수"));
        assertEquals(0.5, s.details().get("전력점수"), 1e-9, "점수는 의도된 중립값이라 채운다");
    }

    /** 계측된 노드는 모든 항목이 채워진다. */
    @DisplayName("계측되면 모든 항목이 채워진다")
    @Test
    void measured_fills_every_field() {
        List<NodeResource> nodes = List.of(node("ipmi", 40, 40, 250, 55));

        NodeScore s = scheduler.schedule(workload(2), nodes, policy()).candidateScores().get(0);

        assertEquals(1.0, s.details().get("전력계측"), 1e-9);
        assertEquals(1.0, s.details().get("온도계측"), 1e-9);
        for (String key : s.details().keySet()) {
            assertNotNull(s.details().get(key), key + " 가 비어 있다");
        }
    }

    // ── 배치 점수용 PEI의 코어 비율 ────────────────────────────

    /**
     * 12·36코어(평균 24) · 사용률 40% · 200W이면 정정 전 PEI 0.4에 비율 0.5 · 1.5를 곱해 상세 PEI가 0.2 · 0.6이다.
     * 비율은 PEI 항에만 곱하므로 비낭비·한계 전력 칸은 그 노드만 후보로 둔 실행과 같다.
     */
    @DisplayName("코어 수가 섞인 후보의 상세 PEI 는 코어 비율을 곱한 값이고 비낭비·실질한계전력점수 값은 그대로다")
    @Test
    void mixed_core_candidates_scale_only_the_pei_detail_by_the_core_ratio() {
        SchedulingResult mixed = scheduler.schedule(workload(2),
                List.of(coreNode("small", 12, 40, 40, 200, 500), coreNode("large", 36, 40, 40, 200, 500)), policy());
        SchedulingResult smallAlone = scheduler.schedule(workload(2), List.of(coreNode("small", 12, 40, 40, 200, 500)), policy());
        SchedulingResult largeAlone = scheduler.schedule(workload(2), List.of(coreNode("large", 36, 40, 40, 200, 500)), policy());

        assertEquals(0.2, detail(mixed, "small", "PEI"), 0.0, "0.4 × 12/24");
        assertEquals(0.6, detail(mixed, "large", "PEI"), 0.0, "0.4 × 36/24");
        assertEquals(0.4, detail(smallAlone, "small", "PEI"), 0.0, "후보가 하나면 비율 1");
        assertEquals(0.4, detail(largeAlone, "large", "PEI"), 0.0, "후보가 하나면 비율 1");
        for (String key : List.of("비낭비", "실질한계전력점수")) {
            assertEquals(detail(smallAlone, "small", key), detail(mixed, "small", key), 0.0, key + " 는 비율을 곱하지 않는다");
            assertEquals(detail(largeAlone, "large", key), detail(mixed, "large", key), 0.0, key + " 는 비율을 곱하지 않는다");
        }
    }

    /**
     * 평균을 내는 후보는 그 호출의 후보 전체다 — 메모리가 모자라 제외된 36코어, 전력을 계측하지 못한 4코어, 정격을 모르는 20코어도
     * 센다. 평균은 (12 + 36 + 4 + 20) / 4 = 18이고, 적격 후보만 세면 12, 전력 계측 후보만 세면 24라 상세 PEI가 달라진다.
     */
    @DisplayName("코어 평균은 제외된 노드와 전력 미계측 노드를 포함한 후보 전체로 낸다")
    @Test
    void the_core_mean_counts_every_candidate_including_excluded_and_unmeasured_nodes() {
        PowerModel model = new PowerModel(new SchedulerProperties());
        NodeResource eligible = coreNode("eligible", 12, 40, 40, 200, 500);
        List<NodeResource> candidates = List.of(eligible,
                coreNode("memory-short", 36, 40, 95, 200, 500),              // 메모리 여유 5% = 819MB < 요구 2048MB
                coreNode("unmeasured", 4, 40, 40, 0, 500),
                coreNode("unrated", 20, 40, 40, 200, 0));
        SchedulingResult result = scheduler.schedule(workload(2), candidates, policy());

        Set<String> eligibleIds = result.candidateScores().stream().filter(NodeScore::eligible).map(NodeScore::nodeId)
                .collect(Collectors.toSet());
        assertEquals(Set.of("eligible", "unmeasured", "unrated"), eligibleIds, "메모리가 모자란 노드만 제외된다");
        assertEquals(18.0, PowerModel.meanPositiveCores(candidates), 0.0);
        assertEquals(round4(model.placementPei(eligible, 18.0)), detail(result, "eligible", "PEI"), 0.0, "0.4 × 12/18");
        assertNotEquals(round4(model.placementPei(eligible, 12.0)), detail(result, "eligible", "PEI"), "적격 후보만의 평균 12 가 아니다");
        assertNotEquals(round4(model.placementPei(eligible, 24.0)), detail(result, "eligible", "PEI"), "전력 계측 후보만의 평균 24 가 아니다");
    }

    /**
     * 코어 수가 모두 같으면 정정 전과 같다 — 제외된 노드와 예약이 있는 노드를 섞은 12코어 3대에서 각 노드의 상세 PEI는 한 인자 판과 같고,
     * 총점은 그 노드만 후보로 둔 실행과 같으며, 선택은 그 총점이 가장 높은 적격 노드다.
     */
    @DisplayName("코어 수가 모두 같은 후보에서 상세 PEI·총점·선택은 정정 전과 같다")
    @Test
    void homogeneous_candidates_keep_pei_totals_and_selection_unchanged() {
        PowerModel model = new PowerModel(new SchedulerProperties());
        List<String> ids = List.of("idle", "short", "busy");
        SchedulingResult together = scheduler.schedule(workload(2), homogeneousNodes(), policy());

        String expectedWinner = null;
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < ids.size(); i++) {
            NodeResource node = homogeneousNodes().get(i);
            NodeScore alone = scheduler.schedule(workload(2), List.of(node), policy()).candidateScores().get(0);
            NodeScore inGroup = together.candidateScores().stream().filter(s -> s.nodeId().equals(node.getNodeId())).findFirst().orElseThrow();

            assertEquals(round4(model.placementPei(node)), inGroup.details().get("PEI"), 0.0, node.getNodeId() + " 상세 PEI = 한 인자 판");
            assertEquals(alone.totalScore(), inGroup.totalScore(), 0.0, node.getNodeId() + " 총점 = 그 노드만 둔 실행");
            assertEquals(alone.eligible(), inGroup.eligible(), node.getNodeId() + " 적격 여부");
            if (alone.eligible() && alone.totalScore() > best) {
                best = alone.totalScore();
                expectedWinner = node.getNodeId();
            }
        }
        assertFalse(together.candidateScores().stream().filter(s -> s.nodeId().equals("short")).findFirst().orElseThrow().eligible(),
                "메모리가 모자란 노드는 제외된다");
        assertEquals(expectedWinner, together.selectedNodeId());
    }

    /** 12코어 3대 — 유휴 · 메모리 부족(제외) · 예약 1코어가 잡힌 가동 노드. 부를 때마다 새 객체를 만든다. */
    private List<NodeResource> homogeneousNodes() {
        NodeResource busy = coreNode("busy", 12, 50, 40, 300, 500);
        busy.reserve(1, 0, 0);
        return List.of(coreNode("idle", 12, 5, 40, 160, 500), coreNode("short", 12, 40, 95, 250, 500), busy);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────

    private Double detail(SchedulingResult result, String nodeId, String key) {
        return result.candidateScores().stream()
                .filter(s -> s.nodeId().equals(nodeId))
                .findFirst().orElseThrow()
                .details().get(key);
    }

    private SchedulingPolicy policy() {
        return new SchedulingPolicy("test", "test");
    }

    private Workload workload(int cpu) {
        return new Workload("scoring-job", cpu, 2048);
    }

    private NodeResource node(String nodeId, double cpuUsage, double memUsage,
                              double power, double temp) {
        return new NodeResource("mdc-01", nodeId, 8, 16384, 0,
                cpuUsage, memUsage, 40, power, 500, temp, 1_000_000, 500_000);
    }

    private NodeResource coreNode(String nodeId, int cores, double cpuUsage, double memUsage,
                                  double power, double capacity) {
        return new NodeResource("mdc-01", nodeId, cores, 16384, 0,
                cpuUsage, memUsage, 40, power, capacity, 55, 1_000_000, 500_000);
    }
}

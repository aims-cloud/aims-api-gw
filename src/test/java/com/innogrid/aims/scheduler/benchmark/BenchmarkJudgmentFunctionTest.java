package com.innogrid.aims.scheduler.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.benchmark.BenchmarkRules.GapCellVerdict;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules.GapOutcome;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules.GapVerdict;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules.PairedDifference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 격차 판정 함수가 규칙 문장의 1~4항과 <b>같은 것</b>을 계산하는지 경계값으로 잠근다.
 *
 * <p>함수는 규칙 축의 성분이 아니라 다이제스트로는 봉인되지 않는다 — 문턱을 {@code >}로 바꾸거나 교집합을 빼고 중앙값을 따로
 * 내거나 판정 불가를 실패로 접으면 여기가 먼저 빨간불이 되어야 한다. 값은 전부 손으로 만든 배열이라 판을 돌리지 않는다.</p>
 */
class BenchmarkJudgmentFunctionTest {

    private static final int N = BenchmarkRules.SWEEP_POINTS;

    /** 길이 12의 배열 — null 자리를 그대로 담는다({@code List.of}는 null에서 NPE라 쓰지 않는다). */
    private static List<Double> pts(Double... v) {
        assertEquals(N, v.length, "시험 배열은 지점 수와 같은 길이여야 한다");
        return Arrays.asList(v);
    }

    private static List<Double> constant(double value, int nulls) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < N; i++) out.add(i < nulls ? null : value);
        return out;
    }

    private static List<Double> shifted(List<Double> base, double delta) {
        List<Double> out = new ArrayList<>();
        for (Double v : base) out.add(v == null ? null : v - delta);
        return out;
    }

    private static List<Double> ramp() {
        return pts(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0, 21.0);
    }

    @DisplayName("1항 — 교집합 |I| 가 하한과 같으면 판정 가능, 하나 모자라면 판정 불가")
    @Test
    void the_paired_point_floor_is_inclusive() {
        int floor = BenchmarkRules.JUDGMENT_MIN_PAIRED_POINTS;
        GapCellVerdict at = BenchmarkRules.judgeCell(constant(10.0, N - floor), constant(5.0, 0));
        GapCellVerdict below = BenchmarkRules.judgeCell(constant(10.0, N - floor + 1), constant(5.0, 0));

        assertEquals(floor, at.pairedCount());
        assertTrue(at.judgeable(), "|I| = 하한이면 판정 가능이어야 한다(≥)");
        assertEquals(floor - 1, below.pairedCount());
        assertFalse(below.judgeable());
        assertFalse(below.medianReduced(), "판정 불가 셀은 2항을 만족했다고 적지 않는다");
        assertFalse(below.pointsReduced());
        assertFalse(below.directionHolds());
        assertEquals(N - floor + 1, below.excludedB(), "결측 수를 셀별로 적시해야 판정 불가 보고가 가능하다");
        assertEquals(0, below.excludedN());
    }

    @DisplayName("2항 — 중앙값 축소는 문턱과 같으면 충족(≤), 0.0001 모자라면 미충족")
    @Test
    void the_median_reduction_threshold_is_inclusive() {
        double pp = BenchmarkRules.JUDGMENT_MEDIAN_REDUCTION_PP;
        List<Double> b = ramp();

        assertTrue(BenchmarkRules.judgeCell(b, shifted(b, pp)).medianReduced(), "정확히 문턱만큼 줄면 충족");
        assertFalse(BenchmarkRules.judgeCell(b, shifted(b, pp - 0.0001)).medianReduced(), "문턱에 못 미치면 미충족");
        assertEquals(pp, BenchmarkRules.judgeCell(b, shifted(b, pp)).reductionPp(), 1e-12, "축소 폭은 B − N 이며 양수가 축소다");
    }

    /**
     * 두 중앙값은 각각 4자리라 차는 0.0001의 배수인데, 부동소수 뺄셈은 그 아래 자리에 잡음을 남긴다 — 2.0012 − 0.0012는
     * 1.9999999999999998이다. 반올림 없이 견주면 정확히 문턱만큼 줄어든 실행이 미달로 접힌다.
     */
    @DisplayName("2항 — 비교는 4자리로 반올림한 차로 한다: 2.0012 → 0.0012 는 잡음 아래 1.9999…이지만 축소 2.0 으로 충족이다")
    @Test
    void the_threshold_comparison_uses_the_rounded_difference() {
        GapCellVerdict v = BenchmarkRules.judgeCell(constant(2.0012, 0), constant(0.0012, 0));

        assertTrue(2.0012 - 0.0012 < 2.0, "이 시험의 전제 — 원시 뺄셈은 2.0 아래로 떨어진다");
        assertEquals(2.0, v.reductionPp(), 1e-12);
        assertTrue(v.medianReduced(), "반올림 없이 견주면 잡음이 판정을 뒤집는다");
    }

    /**
     * 4자리 눈금 한 칸(0.0001)은 실수 항등인 변경이 반올림 동점을 뒤집어 만들 수 있는 차라 축소로 세지 않는다. 두 칸(0.0002)부터 축소다 —
     * 경계는 포함(≥). 무시 폭 안의 차는 동률로 센다. 4항의 방향 셀도 같은 폭이다.
     */
    @DisplayName("3·4항 — 4자리 한 칸 차는 동률, 두 칸부터 축소이며 경계는 포함이다")
    @Test
    void one_display_step_is_a_tie_and_two_steps_are_a_reduction() {
        List<Double> b = ramp();
        GapCellVerdict oneStep = BenchmarkRules.judgeCell(b, shifted(b, 0.0001));
        GapCellVerdict twoSteps = BenchmarkRules.judgeCell(b, shifted(b, 0.0002));
        GapCellVerdict oneStepWorse = BenchmarkRules.judgeCell(b, shifted(b, -0.0001));

        GapCellVerdict twoStepsWorse = BenchmarkRules.judgeCell(b, shifted(b, -0.0002));
        GapCellVerdict threeSteps = BenchmarkRules.judgeCell(b, shifted(b, 0.0003));
        GapCellVerdict fourSteps = BenchmarkRules.judgeCell(b, shifted(b, 0.0004));

        assertEquals(0, oneStep.reducedPoints(), "한 칸 차는 반올림 동점 뒤집힘과 구별되지 않는다");
        assertEquals(N, oneStep.tiedPoints());
        assertFalse(oneStep.directionHolds(), "4항도 한 칸 차로는 방향이 성립하지 않는다");
        assertEquals(N, twoSteps.reducedPoints(), "두 칸부터 축소 — 경계 포함");
        assertEquals(0, twoSteps.tiedPoints());
        assertFalse(twoSteps.directionHolds(), "4항은 중앙값 축이라 두 칸 잡음이 가능 — 폭의 두 배 미만은 방향이 아니다");
        assertFalse(threeSteps.directionHolds(), "타이트 하한은 세 칸이지만 여유 한 칸을 둔 결과 — 세 칸은 아직 방향이 아니다");
        assertTrue(fourSteps.directionHolds(), "무시 폭의 두 배(네 칸)부터 방향 성립 — 경계 포함");
        assertEquals(0, oneStepWorse.reducedPoints());
        assertEquals(N, oneStepWorse.tiedPoints(), "한 칸 악화도 동률이다 — 악화로 세면 잡음이 방향을 만든다");
        assertEquals(0, oneStepWorse.worsenedPoints());
        assertEquals(0, twoStepsWorse.reducedPoints());
        assertEquals(0, twoStepsWorse.tiedPoints(), "두 칸 악화는 동률이 아니다 — 동률로 접으면 악화가 보고에서 사라진다");
        assertEquals(N, twoStepsWorse.worsenedPoints());
        for (GapCellVerdict v : List.of(oneStep, twoSteps, oneStepWorse, twoStepsWorse, threeSteps)) {
            assertEquals(v.pairedCount(), v.reducedPoints() + v.tiedPoints() + v.worsenedPoints(), "회계 항등 — 세 칸의 합은 |I| 다");
        }
    }

    @DisplayName("3항 — 엄격히 작아진 지점만 세고 동률은 축소가 아니며 음수로 내려간 것도 축소다")
    @Test
    void reduced_points_are_strict_and_signed() {
        List<Double> b = ramp();
        List<Double> n = new ArrayList<>(b);
        n.set(0, b.get(0));            // 동률 — 축소 아님
        n.set(1, b.get(1) + 1.0);      // 악화
        for (int i = 2; i < N; i++) n.set(i, -5.0);   // 음수로 — 축소

        GapCellVerdict v = BenchmarkRules.judgeCell(b, n);
        assertEquals(N - 2, v.reducedPoints());
        assertEquals(1, v.tiedPoints());
        assertEquals(1, v.worsenedPoints(), "악화 지점은 따로 센다");
        assertEquals((int) Math.ceil(BenchmarkRules.JUDGMENT_REDUCED_POINT_RATIO * N), v.requiredPoints(), "필요 지점 수는 ceil(비율 × |I|)");
        assertTrue(v.pointsReduced(), "10/12 이상이면 충족");

        n.set(2, b.get(2));            // 동률 하나 더 → 9/12
        GapCellVerdict fewer = BenchmarkRules.judgeCell(b, n);
        assertEquals(N - 3, fewer.reducedPoints());
        assertFalse(fewer.pointsReduced(), "동률을 축소로 세면 여기서 통과해 버린다");
    }

    @DisplayName("중앙값은 교집합 I 위에서 낸다 — 한쪽에만 있는 지점은 두 중앙값 모두에서 빠진다")
    @Test
    void medians_are_taken_on_the_intersection() {
        List<Double> b = ramp();               // 전체 중앙값 15.5
        List<Double> n = shifted(b, 0.0);
        n.set(N - 1, null);                    // N의 마지막(21.0) 결측

        GapCellVerdict v = BenchmarkRules.judgeCell(b, n);
        assertEquals(N - 1, v.pairedCount());
        assertEquals(0, v.excludedB());
        assertEquals(1, v.excludedN());
        assertEquals(15.0, v.medianB(), 1e-12, "B 의 중앙값도 I 위(10..20)에서 낸다 — 전 지점 중앙값 15.5 가 아니다");
        assertEquals(15.0, v.medianN(), 1e-12);
        assertEquals(0.0, v.reductionPp(), 1e-12, "같은 I 위에서 두 값이 같으면 축소 0");
    }

    @DisplayName("길이가 지점 수와 다르거나 null 배열이면 거부한다 — 지수가 대응하지 않는 배열은 판정 자료가 아니다")
    @Test
    void arrays_must_have_sweep_length() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRules.judgeCell(List.of(1.0, 2.0), constant(1.0, 0)));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRules.judgeCell(constant(1.0, 0), null));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRules.medianOfPairedDifferences(constant(1.0, 0), new ArrayList<>()));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRules.judgeGap(null, BenchmarkRules.judgeCell(ramp(), ramp())));
    }

    @DisplayName("결합 — 한 셀이라도 판정 불가면 전체 판정 불가이고 성공이 아니다")
    @Test
    void any_unjudgeable_cell_makes_the_whole_unjudgeable() {
        GapCellVerdict good = BenchmarkRules.judgeCell(ramp(), shifted(ramp(), 5.0));
        GapCellVerdict thin = BenchmarkRules.judgeCell(constant(10.0, 3), constant(1.0, 0));

        GapVerdict v = BenchmarkRules.judgeGap(good, thin);
        assertEquals(GapOutcome.UNJUDGEABLE, v.outcome());
        assertTrue(v.reason().startsWith("1항"), v.reason());
        assertEquals(GapOutcome.UNJUDGEABLE, BenchmarkRules.judgeGap(thin, good).outcome(), "판정 셀이 판정 불가여도 같다");
    }

    @DisplayName("결합 — 판정 셀이 2·3항을 넘고 방향 셀 중앙값이 줄면 성공, 방향 셀이 줄지 않으면 4항 실패")
    @Test
    void direction_mismatch_is_a_failure_not_a_pass() {
        GapCellVerdict judgment = BenchmarkRules.judgeCell(ramp(), shifted(ramp(), 5.0));
        GapCellVerdict directionDown = BenchmarkRules.judgeCell(ramp(), shifted(ramp(), 0.01));
        GapCellVerdict directionFlat = BenchmarkRules.judgeCell(ramp(), ramp());
        GapCellVerdict directionUp = BenchmarkRules.judgeCell(ramp(), shifted(ramp(), -1.0));

        assertEquals(GapOutcome.PASS, BenchmarkRules.judgeGap(judgment, directionDown).outcome(), "방향 셀은 무시 폭의 두 배 밖에서 크기 문턱 없이 부호만 본다");
        assertEquals(GapOutcome.FAIL, BenchmarkRules.judgeGap(judgment, directionFlat).outcome(), "같으면 줄지 않은 것이다");
        GapCellVerdict directionNoise = BenchmarkRules.judgeCell(ramp(), shifted(ramp(), 0.0003));
        assertEquals(GapOutcome.FAIL, BenchmarkRules.judgeGap(judgment, directionNoise).outcome(), "무시 폭의 두 배 미만은 방향이 아니다");
        GapVerdict up = BenchmarkRules.judgeGap(judgment, directionUp);
        assertEquals(GapOutcome.FAIL, up.outcome());
        assertTrue(up.reason().startsWith("4항"), up.reason());
    }

    @DisplayName("결합 — 2항이 모자라면 2항 실패, 2항은 넘고 3항이 모자라면 3항 실패")
    @Test
    void failures_name_the_clause_that_stopped_them() {
        GapCellVerdict direction = BenchmarkRules.judgeCell(ramp(), shifted(ramp(), 1.0));

        GapCellVerdict tooSmall = BenchmarkRules.judgeCell(ramp(), shifted(ramp(), 1.0));
        assertTrue(BenchmarkRules.judgeGap(tooSmall, direction).reason().startsWith("2항"));

        // 중앙값은 충분히 줄이되 축소 지점은 절반만 — 절반을 크게 줄이면 중앙값이 내려간다
        List<Double> n = new ArrayList<>(ramp());
        for (int i = 0; i < N; i++) n.set(i, i % 2 == 0 ? ramp().get(i) - 20.0 : ramp().get(i));
        GapCellVerdict halfReduced = BenchmarkRules.judgeCell(ramp(), n);
        assertTrue(halfReduced.medianReduced(), halfReduced.toString());
        assertFalse(halfReduced.pointsReduced());
        assertTrue(BenchmarkRules.judgeGap(halfReduced, direction).reason().startsWith("3항"));
    }

    @DisplayName("항등 — 기준선을 자기 자신과 견주면 축소 0·동률 전 지점·2항 실패다 (기준선 값이 무엇이든)")
    @Test
    void a_baseline_against_itself_fails_with_zero_reduction() {
        List<Double> b = ramp();
        b.set(4, null);
        GapCellVerdict v = BenchmarkRules.judgeCell(b, b);

        assertEquals(N - 1, v.pairedCount());
        assertEquals(0.0, v.reductionPp(), 1e-12);
        assertEquals(0, v.reducedPoints());
        assertEquals(N - 1, v.tiedPoints());
        assertTrue(v.judgeable());
        assertFalse(v.medianReduced());
        assertFalse(v.directionHolds());
        assertEquals(GapOutcome.FAIL, BenchmarkRules.judgeGap(v, v).outcome(), "변화 없음은 성공이 아니다");
    }

    @DisplayName("민감도 추정량 — 교집합 위 지점별 차의 중앙값이며 두 중앙값의 차와 다른 수다")
    @Test
    void paired_differences_are_not_the_difference_of_medians() {
        List<Double> a = pts(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 100.0);
        List<Double> b = pts(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null);

        PairedDifference d = BenchmarkRules.medianOfPairedDifferences(a, b);
        assertEquals(N - 1, d.pairedCount());
        assertEquals(0, d.excludedA());
        assertEquals(1, d.excludedB());
        assertEquals(10.0, d.median(), 1e-12);
        // 두 중앙값을 따로 내어 빼면 a의 전체 중앙값(10.0) − b의 전체 중앙값(0.0)도 10.0이지만, a의 마지막 지점을 살리면 다르다
        List<Double> bFull = pts(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 50.0);
        PairedDifference full = BenchmarkRules.medianOfPairedDifferences(a, bFull);
        assertEquals(10.0, full.median(), 1e-12, "지점별 차 11개가 10 이고 하나가 50 — 중앙값은 10");
        assertNull(BenchmarkRules.medianOfPairedDifferences(constant(1.0, N), constant(1.0, 0)).median(), "교집합이 비면 null");
    }

    @DisplayName("중앙값 — null 을 빼고 짝수 개는 평균, 응답과 같은 4자리 반올림")
    @Test
    void median_matches_the_response_rounding() {
        assertEquals(1.2346, BenchmarkRules.median(Arrays.asList(1.23455, null, 1.23455)), 1e-12);
        assertEquals(2.5, BenchmarkRules.median(List.of(4.0, 1.0, 2.0, 3.0)), 1e-12);
        assertNull(BenchmarkRules.median(Arrays.asList(null, null)));
    }
}

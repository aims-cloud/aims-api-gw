package com.innogrid.aims.scheduler.benchmark;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric.Direction;
import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric.Role;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 판정 규칙이 <b>결과보다 먼저</b> 고정됐는지 지킨다.
 *
 * <p>이 테스트의 목적은 회귀 방지가 아니라 <b>사전 등록의 강제</b>다. 지표를 나중에 추가·삭제·
 * 방향 반전하면 여기가 빨간불이 되므로, 결과를 보고 기준을 조용히 바꿀 수 없다.</p>
 */
class BenchmarkRulesContractTest {

    /** 사전 등록된 지표 키 — 이 집합이 바뀌면 규칙이 바뀐 것이다. */
    private static final Set<String> DECLARED_KEYS = new TreeSet<>(Set.of(
            "placementRatePercent", "groundTruthPowerW", "perWorkloadPowerW",
            "engineBelievedCostW", "activeNodeDelta", "warnZoneNodeCount",
            "c3InterventionCount", "measuredTempStdDev",
            "unmeasuredPlacementRate", "unmeasuredRateSpreadPercentPoints"));

    @DisplayName("사전 등록된 지표 키 집합이 고정된다")
    @Test
    void metric_keys_are_fixed() {
        Set<String> actual = BenchmarkRules.METRICS.stream()
                .map(BenchmarkMetric::key).collect(Collectors.toCollection(TreeSet::new));

        assertEquals(DECLARED_KEYS, actual,
                "지표를 추가·삭제하려면 이 테스트를 먼저 고쳐야 한다 — 결과를 보고 몰래 바꿀 수 없게");
    }

    @DisplayName("지표마다 정의와 단위와 유효성 조건이 비어 있지 않다")
    @Test
    void every_metric_is_fully_declared() {
        for (BenchmarkMetric m : BenchmarkRules.METRICS) {
            assertFalse(m.key().isBlank(), "key 가 비었다");
            assertFalse(m.label().isBlank(), m.key() + ": label 이 비었다");
            assertNotNull(m.unit(), m.key() + ": unit 은 무차원이어도 빈 문자열이어야 한다");
            assertNotNull(m.direction(), m.key() + ": 좋은 방향이 없다");
            assertNotNull(m.role(), m.key() + ": 역할이 없다");
            assertFalse(m.validity().isBlank(),
                    m.key() + ": 이 숫자가 언제 의미를 갖는지 적지 않으면 셀의 valid 판정 근거가 없다");
        }
    }

    /**
     * 엔진이 스스로 믿은 비용은 <b>전력 엔진 총점의 한 항을 그대로 합한 값</b>이다.
     * 그것을 순위 근거로 쓰면 "우리가 1등"은 발견이 아니라 정의가 된다.
     */
    @DisplayName("엔진이 믿은 비용은 순위 근거로 쓰지 않는다")
    @Test
    void engine_believed_cost_is_context_only() {
        BenchmarkMetric m = metric(BenchmarkRules.ENGINE_BELIEVED_COST);

        assertEquals(Direction.CONTEXT_ONLY, m.direction(),
                "엔진 목적함수와 같은 함수를 좋은 방향이 있는 지표로 두면 순환 논증이다");
        assertEquals(Role.SECONDARY, m.role(), "헤드라인이 될 수 없다");
    }

    @DisplayName("총전력의 주 지표는 지상진실이다")
    @Test
    void ground_truth_power_is_primary() {
        BenchmarkMetric m = metric(BenchmarkRules.GROUND_TRUTH_POWER);

        assertEquals(Role.PRIMARY, m.role());
        assertEquals(Direction.LOWER_IS_BETTER, m.direction());
    }

    @DisplayName("정직성 측정치는 방향이 없다 — 판정이 아니라 숫자다")
    @Test
    void honesty_metrics_have_no_direction() {
        List<BenchmarkMetric> honesty = BenchmarkRules.METRICS.stream()
                .filter(m -> m.role() == Role.HONESTY).toList();

        assertEquals(2, honesty.size(), "미계측 비율과 그 격차 두 개여야 한다");
        for (BenchmarkMetric m : honesty) {
            assertEquals(Direction.CONTEXT_ONLY, m.direction(),
                    m.key() + ": 정직성 측정치에 좋은 방향을 주면 그것이 목표가 된다");
        }
    }

    /**
     * 최고 온도를 주 지표에서 뺀 것은 의도다. 배치 1건의 상승분(약 1.4℃)보다 시각에 따른
     * 자연 변동(±4.5℃)이 3배 크고, 제약 C3 가 임계 이하로 잘라 한 비트짜리 열이 된다.
     */
    @DisplayName("최고 온도는 지표 목록에 없다")
    @Test
    void max_temperature_is_not_a_metric() {
        Set<String> keys = BenchmarkRules.METRICS.stream()
                .map(BenchmarkMetric::key).collect(Collectors.toSet());

        assertFalse(keys.contains("maxTemperatureCelsius"),
                "제약이 임계로 자르는 값은 알고리즘을 재지 않고 제약이 켜졌는지를 잰다");
        assertFalse(keys.contains("avgTemperatureCelsius"),
                "평균은 배치로 거의 움직이지 않는다 — 표준편차를 쓴다");
    }

    @DisplayName("승자를 지명하는 지표를 두지 않는다")
    @Test
    void no_winner_metric() {
        for (BenchmarkMetric m : BenchmarkRules.METRICS) {
            assertFalse(m.key().toLowerCase().contains("winner"),
                    "승자를 API 가 계산해 주면 그 필드만 인용되고, 판 이름에서 분리되면 거짓이 된다");
        }
    }

    @DisplayName("부하율은 네 지점이고 오름차순이며 수용 불가 부하를 포함한다")
    @Test
    void load_ratios_ascend_and_include_overload() {
        List<Integer> r = BenchmarkRules.LOAD_RATIOS;

        assertEquals(List.of(30, 60, 90, 110), r);
        assertTrue(r.get(r.size() - 1) > 100,
                "100%를 넘는 지점이 없으면 성공률이 전략을 구분하지 못한다");
        for (int i = 1; i < r.size(); i++) {
            assertTrue(r.get(i) > r.get(i - 1), "부하율이 오름차순이어야 집합이 접두부로 겹친다");
        }
    }

    @DisplayName("무작위 시드는 서로 다른 홀수 개다")
    @Test
    void random_seeds_are_odd_count_and_distinct() {
        List<Long> seeds = BenchmarkRules.RANDOM_SEEDS;

        assertEquals(31, seeds.size(), "시드 하나는 표본 1개라 격차를 주장할 수 없다");
        assertEquals(seeds.size(), Set.copyOf(seeds).size(), "시드가 중복되면 표본 수가 부풀려진다");
        assertEquals(1, seeds.size() % 2, "홀수라야 중앙값이 잘 정의된다");
    }

    @DisplayName("시각 스윕 지점이 둘 이상이다")
    @Test
    void sweep_has_more_than_one_point() {
        assertTrue(BenchmarkRules.SWEEP_POINTS > 1,
                "시각을 하나로 고정하면 결정론적 전략의 분산 추정치가 0 이 되어 오차 막대를 붙일 수 없다");
    }

    @DisplayName("벤치마크는 냉각 대기를 쓰지 않는다")
    @Test
    void cooldown_is_disabled_for_benchmark() {
        assertEquals(0, BenchmarkRules.COOLDOWN_MINUTES_FOR_BENCHMARK,
                "히스테리시스는 시계열 운영의 성질이라 단발 비교에 넣으면 셀 실행에 걸린 실제 시간이 결과를 바꾼다");
    }

    @DisplayName("배치 건수가 같을 때만 총량을 비교한다")
    @Test
    void totals_comparable_only_when_placed_counts_match() {
        assertTrue(BenchmarkRules.totalsComparable(List.of(20, 20, 20)));
        assertFalse(BenchmarkRules.totalsComparable(List.of(20, 19, 20)),
                "적게 배치하고 전력을 덜 쓰는 것은 승리가 아니다");
        assertFalse(BenchmarkRules.totalsComparable(List.of()), "빈 입력으로 유효를 주장할 수 없다");
        assertFalse(BenchmarkRules.totalsComparable(null));
    }

    @DisplayName("총량을 비교할 수 없는 사유에 실제 건수 범위가 들어간다")
    @Test
    void incomparable_reason_carries_the_range() {
        String reason = BenchmarkRules.totalsIncomparableReason(Map.of("a", 18, "b", 20, "c", 19));

        assertTrue(reason.contains("18"), reason);
        assertTrue(reason.contains("20"), reason);
    }

    @DisplayName("미계측 격차 경고에 격차와 양쪽 전략이 들어간다")
    @Test
    void unmeasured_caution_names_the_gap_and_strategies() {
        String s = BenchmarkRules.unmeasuredCaution(31.5, "random", "power");

        assertTrue(s.contains("31.5"), s);
        assertTrue(s.contains("random"), s);
        assertTrue(s.contains("power"), s);
        assertTrue(s.contains("센서"), "왜 문제인지가 문장에 있어야 각주 없이 읽힌다: " + s);
    }

    @DisplayName("한계 문구가 실측이 아님과 노드 한 대를 밝힌다")
    @Test
    void limitation_states_the_two_hard_limits() {
        String l = BenchmarkRules.LIMITATION;

        assertTrue(l.contains("실측이 아니"), l);
        assertTrue(l.contains("생성식"), "지상진실도 우리가 만든 식이라는 사실이 있어야 한다: " + l);
        assertTrue(l.contains("한 대"), "배포 환경에서 비교가 성립하지 않음이 있어야 한다: " + l);
    }

    @DisplayName("부하율 분모 정의가 총 코어가 아니라 정책 상한까지의 여유다")
    @Test
    void headroom_definition_uses_policy_limits() {
        String d = BenchmarkRules.HEADROOM_DEFINITION;

        assertTrue(d.contains("상한"), d);
        assertTrue(d.contains("min"), "노드마다 구속 자원이 달라 두 축의 최솟값을 써야 한다: " + d);
    }

    private static BenchmarkMetric metric(String key) {
        return BenchmarkRules.METRICS.stream().filter(m -> m.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError(key + " 지표가 선언되지 않았다"));
    }
}

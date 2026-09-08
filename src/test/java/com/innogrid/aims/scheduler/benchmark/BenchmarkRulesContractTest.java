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
            "placementRatePercent", "generatorPowerW", "perWorkloadPowerW",
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

    /**
     * 평가자도 생성식을 쓴다. "지상진실"이라는 이름은 실측을 암시하고, 표만 잘라 인용되면 그 암시가
     * 사실로 굳는다 — 이름·라벨 어디에도 그 단어가 없어야 한다.
     */
    @DisplayName("총전력의 주 지표는 엔진 점수가 아니라 생성식 기준값이고, 실측을 암시하는 이름을 쓰지 않는다")
    @Test
    void generator_power_is_primary_and_not_named_ground_truth() {
        BenchmarkMetric m = metric(BenchmarkRules.GENERATOR_POWER);

        assertEquals(Role.PRIMARY, m.role());
        assertEquals(Direction.LOWER_IS_BETTER, m.direction());
        assertFalse(m.key().toLowerCase().contains("groundtruth"), m.key());
        for (BenchmarkMetric each : BenchmarkRules.METRICS) {
            assertFalse(each.label().contains("지상진실"),
                    each.key() + ": 평가자도 생성식을 쓰므로 '지상진실'은 실측을 암시한다 — " + each.label());
            assertFalse(each.label().contains("실제"),
                    each.key() + ": '실제'도 같은 암시다 — " + each.label());
        }
    }

    /**
     * 열 계열 두 지표는 <b>헤드라인이 아니다</b>. 대조군은 열을 점수에 넣지 않으므로 이 값의 차이는
     * 알고리즘의 열 판단이 아니라 CPU 를 어디에 몰았는가의 부산물이고, 제약 개입 횟수는 게이트가
     * 몇 번 걸렸는지이지 좋고 나쁨이 아니다. "열 모델이 없어 비교 불가"라고 적지 않는다 — 대조군도
     * 온도 제약은 똑같이 받는다({@code every_baseline_scores_all_candidates} 가 그 사실을 증명한다).
     */
    @DisplayName("열 계열 두 지표는 헤드라인이 아니고 개입 횟수에는 좋은 방향이 없다")
    @Test
    void the_thermal_metrics_are_not_headline_numbers() {
        BenchmarkMetric warn = metric(BenchmarkRules.WARN_ZONE_NODES);
        BenchmarkMetric interventions = metric(BenchmarkRules.C3_INTERVENTIONS);

        assertEquals(Role.SECONDARY, warn.role(), "주 지표로 두면 이 판의 결론이 열에 실린다");
        assertEquals(Direction.LOWER_IS_BETTER, warn.direction(), "적은 쪽이 실제로 덜 뜨거운 것은 맞다");

        assertEquals(Role.SECONDARY, interventions.role());
        assertEquals(Direction.CONTEXT_ONLY, interventions.direction(),
                "덜 막힌 쪽이 낫다고 두면 열을 아예 보지 않는 전략이 상을 받는다");

        for (BenchmarkMetric m : List.of(warn, interventions)) {
            assertTrue(m.validity().contains("점수에 넣지 않"),
                    m.key() + ": 대조군이 열을 점수에 넣지 않는다는 사실이 유효성 조건에 있어야 한다");
            assertFalse(m.validity().contains("열 모델이 없"),
                    m.key() + ": 대조군도 온도 제약은 받는다 — 코드가 증명하는 사실과 어긋나는 문구다");
        }
    }

    /**
     * 주 지표가 어떤 것들인지 목록으로 못 박는다 — 조용히 늘거나 줄면 결론의 근거가 바뀐 것이다.
     * 온도 축이 여기 없는 것은 의도다: 대조군이 열을 점수에 넣지 않아 온도 축에 참가자가 우리뿐이고,
     * 그 사실은 {@link BenchmarkRules#LIMITATION} 이 밝힌다.
     */
    @DisplayName("주 지표는 성공률과 전력·가동 계열뿐이고 온도 축에는 없다")
    @Test
    void the_primary_metrics_are_the_placement_rate_and_the_power_family() {
        Set<String> primary = BenchmarkRules.METRICS.stream()
                .filter(m -> m.role() == Role.PRIMARY)
                .map(BenchmarkMetric::key).collect(Collectors.toCollection(TreeSet::new));

        assertEquals(new TreeSet<>(Set.of(BenchmarkRules.PLACEMENT_RATE, BenchmarkRules.GENERATOR_POWER,
                BenchmarkRules.PER_WORKLOAD_POWER, BenchmarkRules.ACTIVE_NODE_DELTA)), primary);
        assertTrue(BenchmarkRules.LIMITATION.contains("온도 축에는 결론을 지지하는 지표가 없다"),
                "주 지표에서 온도를 내렸으면 응답이 그 사실을 스스로 말해야 한다 — 아니면 조용한 강등이다");
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

    /**
     * 시드 목록의 <b>길이가 곧 표본 수</b>라는 주장은 난수원이 시드를 제대로 섞을 때만 참이다.
     *
     * <p>{@code new java.util.Random(seed)} 로는 성립하지 않는다 — 실측하면 시드 1~31 의
     * {@code nextInt(4)} 첫 값이 <b>전부 2</b> 이고 {@code nextDouble()} 첫 값이 0.7298~0.7326
     * 폭 0.003 안에 뭉친다. 그 난수원으로는 "무작위 31회"가 배치 건수가 적은 판에서 사실상 1회다.</p>
     */
    @DisplayName("사전 등록된 시드가 실제로 서로 다른 난수열을 낸다")
    @Test
    void declared_seeds_actually_yield_distinct_streams() {
        Set<Integer> firstOfFour = new java.util.LinkedHashSet<>();
        Set<Long> firstBits = new java.util.LinkedHashSet<>();
        for (long seed : BenchmarkRules.RANDOM_SEEDS) {
            firstOfFour.add(BenchmarkRules.randomFor(seed).nextInt(4));
            firstBits.add(BenchmarkRules.randomFor(seed).nextLong());
        }

        assertEquals(BenchmarkRules.RANDOM_SEEDS.size(), firstBits.size(),
                "시드마다 난수열이 달라야 표본 수가 시드 수와 같다");
        assertTrue(firstOfFour.size() >= 3,
                "네 개 중 하나를 뽑는 첫 추첨이 " + firstOfFour + " 뿐이다 — "
                        + "시드를 섞지 않는 난수원을 쓰면 시드 목록이 표본을 늘려 주지 못한다");
    }

    @DisplayName("같은 시드는 같은 난수열을 낸다")
    @Test
    void same_seed_yields_the_same_stream() {
        long seed = BenchmarkRules.RANDOM_SEEDS.get(0);

        assertEquals(BenchmarkRules.randomFor(seed).nextLong(), BenchmarkRules.randomFor(seed).nextLong(),
                "재현성이 깨지면 커밋된 시드 목록이 아무것도 고정하지 못한다");
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

    @DisplayName("한계 문구가 실측이 아님과 노드 한 대와 무엇을 증명하지 않는지를 밝힌다")
    @Test
    void limitation_states_the_hard_limits_and_what_is_not_proven() {
        String l = BenchmarkRules.LIMITATION;

        assertTrue(l.contains("실측이 아니"), l);
        assertTrue(l.contains("생성식"), "평가자도 우리가 만든 식을 쓴다는 사실이 있어야 한다: " + l);
        assertTrue(l.contains("한 대"), "배포 환경에서 비교가 성립하지 않음이 있어야 한다: " + l);
        assertTrue(l.contains("엔진의 모델 가정과 생성식이 얼마나 다른가"),
                "무엇을 확인하고 무엇을 확인하지 못하는지가 한 문장으로 있어야 인용될 때 떨어져 나가지 않는다: " + l);
        assertTrue(l.contains("결론 수준으로 증명하지 않는다"),
                "온도 축에 결론 지표가 없고 전력 이득이 반사실 판에서만 보인다는 사실을 응답이 스스로 말해야 한다 — "
                        + "말하지 않으면 불리한 지표를 조용히 내린 것으로 읽힌다: " + l);
        assertFalse(l.contains("지상진실"), "한계 문구 자체가 실측을 암시하면 안 된다: " + l);
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

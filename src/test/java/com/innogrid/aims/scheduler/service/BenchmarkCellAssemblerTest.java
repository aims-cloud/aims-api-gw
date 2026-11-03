package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.innogrid.aims.scheduler.benchmark.BaselinePlacers;
import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.benchmark.PlacementStrategy;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.MetricValue;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.service.BenchmarkPointRunner.Placement;
import com.innogrid.aims.scheduler.service.BenchmarkPointRunner.PointOutcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 배치 전략 비교의 지표 무효 사유 — 지표마다 어떤 조건에서 어떤 문장이 어떤 순서로 붙는지.
 *
 * <p>등록된 판의 응답에는 나오지 않는 사유가 있다(계측 노드가 둘 미만 · 전 시각 지점에서 배치 0건 · 기준 집합과 건수가 다른 지점뿐 ·
 * 값이 나오지 않은 셀). 그래서 응답이나 증거 파일로는 이 자리를 지킬 수 없어, 결과 표 조립의 두 메서드를 직접 부른다. 기대 문장은
 * 분리 전 서비스의 문장을 글자 그대로 옮겼다.</p>
 */
class BenchmarkCellAssemblerTest {

    // ── 기대 문장(분리 전 서비스의 문장 그대로) ──────────────────────
    private static final String NO_BASELINE = "기준 전략(전력 인지 최적적합)이 이 실행에 없다";
    private static final String BASELINE_SELF = "기준 전략 자신 — 0 은 값이 아니다";
    private static final String GAP_UNPAIRED = "기준 전략과 배치 건수가 같은 시각 지점이 없다";
    private static final String TOTALS_UNPAIRED =
            "기본 설정 열 전부의 배치 건수가 같은 시각 지점이 없다 — 적게 배치하고 전력을 덜 쓰는 것은 승리가 아니다";
    private static final String THERMAL_UNPAIRED =
            "기본 설정 열 전부의 배치 건수가 같은 시각 지점이 없다 — 적게 배치해 CPU 를 덜 얹은 노드가 덜 뜨거운 것은 열 성능이 아니다";
    private static final String NOTHING_PLACED = "전 시각 지점에서 배치 0건이라 건당 값을 낼 수 없다";
    private static final String THERMAL_GATE_OFF = "이 전략은 온도 제약이 꺼져 있어 항상 0 이다 — 켜진 전략과 비교하지 않는다";
    private static final String GATES_OFF = "이 전략은 온도·전력 제약이 꺼져 있어 막히는 자리가 없다 — 제약을 받는 전략과 성공률을 "
            + "비교하지 않는다. 이 열은 경쟁자가 아니라 '제약이 없었다면 얼마나 배치됐을까'의 천장이다";
    private static final String TOO_FEW_MEASURED = "계측 노드가 둘 미만이라 온도 분포를 말할 수 없다";
    private static final String NO_VALUE = "이 셀에서 값이 나오지 않았다";
    private static final String NO_DATA_MDC = "이 판은 워크로드에 데이터 MDC 를 배정하지 않아 원격 배치가 정의되지 않는다";
    private static final String NO_DATA_MDC_PLACEMENT = "데이터 MDC 가 있는 배치가 전 시각 지점에서 0건이라 원격 배치 비율을 낼 수 없다";

    /**
     * 입력 조합 열두 가지 — 순서대로 ① 기본 · ② 짝 지점 0 · ③ 계측 노드 1 · ④ 짝 지점 0 이면서 계측 노드 1 · ⑤ 전 지점 배치 0 ·
     * ⑥ 온도 제약 꺼짐 · ⑦ 전력 제약 꺼짐 · ⑧ 기준 전략 없음 · ⑨ 기준 전략 자신 · ⑩ 기준과 짝 지점 없음(표본 없음) · ⑪ 기준 없음이면서 자신 ·
     * ⑫ 데이터 MDC 를 배정하지 않은 판.
     */
    private static final List<Combo> COMBOS = List.of(
            new Combo(3, 9, true, true, true, false, true, true),
            new Combo(0, 9, true, true, true, false, true, true),
            new Combo(3, 1, true, true, true, false, true, true),
            new Combo(0, 1, true, true, true, false, true, true),
            new Combo(3, 9, false, true, true, false, true, true),
            new Combo(3, 9, true, true, true, false, false, true),
            new Combo(3, 9, true, true, true, false, true, false),
            new Combo(3, 9, true, false, true, false, true, true),
            new Combo(3, 9, true, true, true, true, true, true),
            new Combo(3, 9, true, true, false, false, true, true),
            new Combo(3, 9, true, false, true, true, true, true),
            new Combo(3, 9, true, true, true, false, true, true, false));

    /** 지표 키 → 조합 ①~⑫ 의 기대 사유(없으면 null). 지표를 더하면 이 표에도 자리를 정해야 한다. */
    private static final Map<String, List<String>> RULE_TABLE = ruleTable();

    private static Map<String, List<String>> ruleTable() {
        Map<String, List<String>> t = new LinkedHashMap<>();
        t.put(BenchmarkRules.GAP_VS_PABFD, rows(null, null, null, null, null, null, null,
                NO_BASELINE, BASELINE_SELF, GAP_UNPAIRED, NO_BASELINE, null));
        t.put(BenchmarkRules.GENERATOR_POWER, rows(null, TOTALS_UNPAIRED, null, TOTALS_UNPAIRED, null, null, null, null, null, null, null, null));
        t.put(BenchmarkRules.ACTIVE_NODE_DELTA, rows(null, TOTALS_UNPAIRED, null, TOTALS_UNPAIRED, null, null, null, null, null, null, null, null));
        t.put(BenchmarkRules.TEMP_STDDEV, rows(null, THERMAL_UNPAIRED, TOO_FEW_MEASURED, THERMAL_UNPAIRED, null, null, null, null, null, null, null, null));
        t.put(BenchmarkRules.WARN_ZONE_NODES, rows(null, THERMAL_UNPAIRED, TOO_FEW_MEASURED, THERMAL_UNPAIRED, null, null, null, null, null, null, null, null));
        t.put(BenchmarkRules.PER_WORKLOAD_POWER, rows(null, null, null, null, NOTHING_PLACED, null, null, null, null, null, null, null));
        t.put(BenchmarkRules.C3_INTERVENTIONS, rows(null, null, null, null, null, THERMAL_GATE_OFF, null, null, null, null, null, null));
        t.put(BenchmarkRules.PLACEMENT_RATE, rows(null, null, null, null, null, GATES_OFF, GATES_OFF, null, null, null, null, null));
        t.put(BenchmarkRules.ENGINE_BELIEVED_COST, rows(null, null, null, null, null, null, null, null, null, null, null, null));
        t.put(BenchmarkRules.UNMEASURED_RATE, rows(null, null, null, null, null, null, null, null, null, null, null, null));
        t.put(BenchmarkRules.UNMEASURED_SPREAD, rows(null, null, null, null, null, null, null, null, null, null, null, null));
        t.put(BenchmarkRules.REMOTE_MDC_RATE, rows(null, null, null, null, null, null, null, null, null, NO_DATA_MDC_PLACEMENT, null,
                NO_DATA_MDC));
        return t;
    }

    private static List<String> rows(String... reasons) {
        List<String> out = new ArrayList<>(reasons.length);
        for (String r : reasons) out.add(r);
        return out;
    }

    static Stream<String> metricKeys() {
        return BenchmarkRules.METRICS.stream().map(BenchmarkMetric::key);
    }

    @DisplayName("비교 지표마다 무효 사유가 정해진 조건과 순서대로 붙는다")
    @ParameterizedTest(name = "무효 사유 {0}")
    @MethodSource("metricKeys")
    void invalid_reason_follows_the_rule_table_for_each_metric(String key) {
        BenchmarkMetric metric = metric(key);
        List<String> expected = RULE_TABLE.get(key);
        List<String> actual = new ArrayList<>();
        for (Combo c : COMBOS) {
            Placement any = placement(c.self() ? pabfd() : BaselinePlacers.ffdBySize(), 1, c.temperatureGated(), c.powerGated());
            actual.add(BenchmarkCellAssembler.invalidReason(metric, any, c.usablePoints(), c.measuredNodes(), c.placedAnywhere(),
                    c.baselinePresent(), c.paired(), c.dataMdcAssigned()));
        }
        assertEquals(expected, actual, key + " — 조합 ①~⑫ 의 무효 사유");
    }

    @DisplayName("무효 사유 표는 등록된 비교 지표와 키가 같다")
    @Test
    void rule_table_lists_exactly_the_registered_metrics() {
        assertEquals(new TreeSet<>(RULE_TABLE.keySet()), new TreeSet<>(metricKeys().toList()),
                "BenchmarkRules.METRICS 에 지표를 더했으면 무효 사유 규칙과 이 표에 그 지표의 자리를 정한다");
    }

    @DisplayName("표본이 빈 지표는 지표마다 정해진 사유와 지점별 값 유무로 나간다")
    @Test
    void empty_metric_values_name_their_reason_by_metric() {
        Placement row = placement(BaselinePlacers.ffdBySize(), 1, true, true);
        List<PointOutcome> points = List.of(point(row), point(row), point(row));
        Set<String> pairedOnly = Set.of(BenchmarkRules.GENERATOR_POWER, BenchmarkRules.ACTIVE_NODE_DELTA,
                BenchmarkRules.WARN_ZONE_NODES, BenchmarkRules.TEMP_STDDEV);

        // 짝 지점 표본이 전부 빠진 경우(제외 지점 수 == 쓸 수 있는 지점 수 > 0)
        List<MetricValue> dropped = BenchmarkCellAssembler.metricValues(Map.of(), row, 2, points, 2);
        assertEquals(BenchmarkRules.METRICS.size(), dropped.size());
        for (MetricValue v : dropped) {
            assertEquals(false, v.valid(), v.key());
            assertEquals(0, v.samples(), v.key());
            if (pairedOnly.contains(v.key())) {
                assertEquals("기준 집합과 배치 건수가 다른 지점뿐이라 총량을 낼 수 없다(제외 2곳)", v.invalidReason(), v.key());
            } else if (BenchmarkRules.GAP_VS_PABFD.equals(v.key())) {
                assertEquals(GAP_UNPAIRED, v.invalidReason(), v.key());
            } else if (BenchmarkRules.REMOTE_MDC_RATE.equals(v.key())) {
                assertEquals(NO_DATA_MDC_PLACEMENT, v.invalidReason(), v.key());
            } else {
                assertEquals(NO_VALUE, v.invalidReason(), v.key());
            }
            if (BenchmarkRules.GAP_VS_PABFD.equals(v.key())) {
                assertEquals(points.size(), v.points().size(), "격차는 지점별 값을 지점 수만큼 싣는다");
                assertTrue(v.points().stream().allMatch(x -> x == null), "표본이 없으면 지점별 값은 모두 빈 자리다");
            } else {
                assertEquals(null, v.points(), v.key() + " — 지점별 값은 격차만 싣는다");
            }
        }

        // 제외 지점 수가 쓸 수 있는 지점 수와 다르면 짝 지점 지표도 값이 나오지 않았다는 문장으로 나간다
        for (MetricValue v : BenchmarkCellAssembler.metricValues(Map.of(), row, 2, points, 1)) {
            if (pairedOnly.contains(v.key())) assertEquals(NO_VALUE, v.invalidReason(), v.key());
        }
    }

    /** 등록된 판에서는 링크 판이 전 시각 지점에서 값을 내 이 경로가 응답으로 지나가지 않는다 — 지점의 null 을 0 으로 세면 비율이 낮아 보인다. */
    @DisplayName("원격 MDC 배치 비율은 일부 시각 지점에만 값이 없으면 남은 지점으로 값을 내고 유효하다")
    @Test
    void remote_mdc_rate_uses_the_points_that_have_a_value() {
        Placement row = placement(BaselinePlacers.ffdBySize(), 1, true, true);
        List<PointOutcome> points = List.of(point(row), point(row), point(row));
        Map<String, List<Double>> samples = Map.of(BenchmarkRules.REMOTE_MDC_RATE, Arrays.asList(null, 40.0, 60.0));

        MetricValue v = BenchmarkCellAssembler.metricValues(samples, row, 3, points, 0).stream()
                .filter(m -> m.key().equals(BenchmarkRules.REMOTE_MDC_RATE)).findFirst().orElseThrow();
        assertTrue(v.valid(), v.invalidReason());
        assertEquals(50.0, v.median(), 1e-9);
        assertEquals(40.0, v.min(), 1e-9);
        assertEquals(60.0, v.max(), 1e-9);
        assertEquals(2, v.samples(), "값이 없는 지점은 표본에서 빠진다");
    }

    // ── 입력 만들기 ──────────────────────────────────────────

    private record Combo(int usablePoints, int measuredNodes, boolean placedAnywhere, boolean baselinePresent,
                         boolean paired, boolean self, boolean temperatureGated, boolean powerGated, boolean dataMdcAssigned) {
        Combo(int usablePoints, int measuredNodes, boolean placedAnywhere, boolean baselinePresent,
              boolean paired, boolean self, boolean temperatureGated, boolean powerGated) {
            this(usablePoints, measuredNodes, placedAnywhere, baselinePresent, paired, self, temperatureGated, powerGated, true);
        }
    }

    private static BenchmarkMetric metric(String key) {
        return BenchmarkRules.METRICS.stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    private static PlacementStrategy pabfd() {
        return BaselinePlacers.pabfd(new PowerModel(new SchedulerProperties()));
    }

    private static Placement placement(PlacementStrategy strategy, int placed, boolean temperatureGated, boolean powerGated) {
        return new Placement(strategy, placed, placed, 0, Set.of(), Map.of(), 0, 0, 0, 0, 0, temperatureGated, powerGated, 0, 0);
    }

    /** 이 전략과 기준 전략이 함께 있고 계측 노드가 아홉이며 데이터 MDC 를 배정한 판의 시각 지점. */
    private static PointOutcome point(Placement row) {
        Map<String, Placement> byStrategy = new LinkedHashMap<>();
        byStrategy.put(row.strategy().id(), row);
        byStrategy.put(BenchmarkColumns.PABFD_ID, placement(pabfd(), 1, true, true));
        return new PointOutcome(0, 1, 1, Map.of(), Set.of(), 0, 9, Map.of(), byStrategy, true);
    }
}

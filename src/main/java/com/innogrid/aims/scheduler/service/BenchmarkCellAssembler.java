package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.innogrid.aims.scheduler.benchmark.BenchmarkMetric;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.benchmark.GroundTruthOracle;
import com.innogrid.aims.scheduler.benchmark.GroundTruthOracle.PowerControl;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.Cell;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.MetricValue;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.StrategyResult;
import com.innogrid.aims.scheduler.service.BenchmarkPointRunner.Placement;
import com.innogrid.aims.scheduler.service.BenchmarkPointRunner.PointOutcome;

import static com.innogrid.aims.scheduler.service.BenchmarkColumns.PABFD_ID;

/**
 * 배치 전략 비교 서비스에서 떼어 낸 <b>결과 표 조립</b> — 한 부하율의 시각 지점 결과를 전원 상태 가정 하나로 회계해 셀(결과 표 한 칸)을
 * 만들고, 지표마다 값과 순위 근거가 될 수 없는 사유를 붙인다.
 *
 * <p>스프링 빈이 아니다. 서비스가 생성자에서 한 번 만들고, 생성자는 참조만 저장한다.</p>
 */
final class BenchmarkCellAssembler {

    private final BenchmarkColumns columns;

    BenchmarkCellAssembler(BenchmarkColumns columns) {
        this.columns = columns;
    }

    Cell cell(int loadRatio, PowerControl mode, List<PointOutcome> points, GroundTruthOracle oracle) {
        List<String> strategyIds = List.copyOf(points.get(0).byStrategy().keySet());

        Map<String, Map<String, List<Double>>> samples = new LinkedHashMap<>();
        for (String id : strategyIds) samples.put(id, new LinkedHashMap<>());

        // 총량 비교는 시각 지점마다 짝짓는다. 어떤 지점에서 기준 집합(override 없는 기본 설정 열) 전부의 배치 건수가 같다면
        // 그 지점의 총량은 견줄 수 있다 — 다른 지점이 어긋났다는 이유로 그 지점까지 버리면 쓸 수 있는 짝을 통째로
        // 잃고 주 지표가 중부하 이상에서 사라진다. 대신 몇 곳을 썼는지 밝힌다. override 열을 기준 집합에 넣지 않는 이유는
        // BenchmarkRules.PAIRING_RULE — 열을 더하는 것만으로 기존 열의 값이 바뀌면 안 된다.
        Map<String, Map<String, String>> overrides = columns.overrides();
        List<String> coreIds = strategyIds.stream().filter(id -> !overrides.containsKey(id)).toList();
        int usablePoints = 0;
        Map<String, Integer> mismatchExample = Map.of();
        // override 열이 기준 집합과 건수가 달라 총량을 내지 못한 지점 수 — PAIRING_RULE 이 응답 표기를 요구한다
        Map<String, Integer> droppedByRow = new LinkedHashMap<>();

        for (PointOutcome point : points) {
            Map<String, Double> unmeasuredRates = new LinkedHashMap<>();
            Map<String, Integer> placedHere = new LinkedHashMap<>();
            for (String id : strategyIds) {
                placedHere.put(id, point.byStrategy().get(id).placed());
            }
            Map<String, Integer> corePlaced = new LinkedHashMap<>();
            for (String id : coreIds) corePlaced.put(id, placedHere.get(id));
            boolean pointComparable = BenchmarkRules.totalsComparable(corePlaced.values());
            Integer coreCount = pointComparable ? corePlaced.values().iterator().next() : null;

            // 생성식 총전력은 지점마다 전략당 한 번 내어 두 열이 같은 값을 읽는다. 총량 열은 짝이 아닌
            // 지점을 null 로 가리지만(아래), 격차 열은 이 행과 기준 전략 둘만 짝지으므로 가리는 조건이
            // 다르다 — 같은 오라클 호출을 두 조건으로 쓰는 것이지 값을 두 번 계산하는 것이 아니다.
            Map<String, Double> powerByStrategy = new LinkedHashMap<>();
            for (String id : strategyIds) {
                powerByStrategy.put(id, power(oracle, point, point.byStrategy().get(id), mode));
            }
            Integer placedBaseline = placedHere.get(PABFD_ID);
            Double powerBaseline = powerByStrategy.get(PABFD_ID);
            if (pointComparable) {
                usablePoints++;
            } else {
                mismatchExample = corePlaced;
            }

            for (String id : strategyIds) {
                Placement p = point.byStrategy().get(id);
                Map<String, List<Double>> into = samples.get(id);
                // override 열은 기준 집합의 마스크를 받되 자기 건수가 기준 건수와 같아야 총량을 낸다
                boolean rowComparable = pointComparable && (coreIds.contains(id) || coreCount.equals(p.placed()));
                if (pointComparable && !rowComparable) droppedByRow.merge(id, 1, Integer::sum);

                add(into, BenchmarkRules.PLACEMENT_RATE,
                        p.requested() == 0 ? null : round(100.0 * p.placed() / p.requested()));
                add(into, BenchmarkRules.PER_WORKLOAD_POWER, perWorkload(oracle, point, p, mode));
                add(into, BenchmarkRules.ENGINE_BELIEVED_COST, round(p.engineBelievedCostW()));
                // 개입 횟수는 시도당 계수다 — 전 전략이 같은 워크로드를 한 번씩 시도하므로 배치 건수와
                // 무관하게 지점마다 센다. 아래 짝짓기 조건을 여기에 걸면 없는 결함을 고치는 것이다.
                add(into, BenchmarkRules.C3_INTERVENTIONS, (double) p.thermalExclusions());

                // 총량 계열과 온도 분포 계열은 건수가 짝지어진 지점에서만 표본에 넣는다. 적게 배치하고 전력을
                // 덜 쓰는 것이 승리가 아닌 것처럼, 적게 배치해 CPU 를 덜 얹은 노드가 덜 뜨거운 것도 열 성능이
                // 아니다 — 생성식 온도는 CPU 의 함수라 배치량이 다르면 열이 아니라 배치량을 재게 된다.
                // 짝이 아닌 지점의 값은 아예 세지 않는다.
                add(into, BenchmarkRules.GENERATOR_POWER,
                        rowComparable ? powerByStrategy.get(id) : null);
                // 격차는 전 전략 짝을 요구하지 않는다 — 이 행과 기준 전략의 건수만 같으면 낸다.
                add(into, BenchmarkRules.GAP_VS_PABFD,
                        gapVsBaseline(id, placedHere.get(id), powerByStrategy.get(id),
                                placedBaseline, powerBaseline));
                add(into, BenchmarkRules.ACTIVE_NODE_DELTA, rowComparable
                        ? (double) (p.activeCountAfter() - point.activeCountBefore()) : null);
                add(into, BenchmarkRules.WARN_ZONE_NODES,
                        rowComparable ? (double) p.warnZoneNodes() : null);
                add(into, BenchmarkRules.TEMP_STDDEV,
                        rowComparable && point.measuredNodeCount() >= 2 ? round(p.measuredTempStdDev()) : null);

                Double rate = p.placed() == 0 ? null : round(100.0 * p.unmeasuredPlacements() / p.placed());
                add(into, BenchmarkRules.UNMEASURED_RATE, rate);
                if (rate != null && coreIds.contains(id)) unmeasuredRates.put(id, rate);
            }

            // 미계측 격차는 기준 집합 사이의 값이라 지점마다 한 번 계산해 전 전략에 같은 값으로 싣는다.
            Double spread = spreadOf(unmeasuredRates);
            for (String id : strategyIds) {
                add(samples.get(id), BenchmarkRules.UNMEASURED_SPREAD, spread);
            }
        }

        boolean comparable = usablePoints == points.size();
        String incomparable = comparable ? null
                : pairingNote(usablePoints, points.size(), mismatchExample);
        if (!droppedByRow.isEmpty()) {
            final int usable = usablePoints;
            List<String> parts = new ArrayList<>();
            droppedByRow.forEach((id, k) -> parts.add(id + " " + k + "곳 제외(표본 " + (usable - k) + ")"));
            String note = "override 열 중 기준 집합과 배치 건수가 다른 지점을 뺀 열: " + String.join(", ", parts)
                    + " — 그 열의 총량 표본(samples)은 그만큼 적다";
            incomparable = incomparable == null ? note : incomparable + ". " + note;
        }

        List<StrategyResult> results = new ArrayList<>(strategyIds.size());
        for (String id : strategyIds) {
            Placement any = points.get(0).byStrategy().get(id);
            results.add(new StrategyResult(id, any.strategy().label(), any.strategy().kind().name(),
                    any.strategy().policy().name(), overrides.getOrDefault(id, Map.of()),
                    metricValues(samples.get(id), any, usablePoints, points, droppedByRow.getOrDefault(id, 0))));
        }

        return new Cell(loadRatio, mode.name(),
                median(points.stream().map(p -> (double) p.requestedWorkloads()).toList()),
                median(points.stream().map(PointOutcome::requestedCores).toList()),
                median(points.stream().map(PointOutcome::headroomCores).toList()),
                median(points.stream().map(p -> (double) p.activeCountBefore()).toList()),
                comparable, incomparable,
                unmeasuredCaution(samples, coreIds),
                probeReasonCounts(points), results);
    }

    /**
     * 이 행의 <b>기준 전략 대비</b> 총전력 격차(%). 기준 전략 자신과 기준이 없는 실행에서는 값을 내지 않는다 —
     * 자기 자신의 0 은 "격차가 없다"가 아니라 값이 아니고, 그 사유는 {@code invalidReason} 이 적는다.
     *
     * <p>짝 조건과 분모 가드는 {@link BenchmarkRules#pairedGapPercent} 가 들고 있다 — 판정에 쓰는 식이라
     * 결과 코드보다 먼저 등록된 자리에 있어야 하고, 여기서는 반올림만 한다.</p>
     */
    private static Double gapVsBaseline(String id, Integer placed, Double power,
                                        Integer placedBaseline, Double powerBaseline) {
        if (PABFD_ID.equals(id) || placed == null || power == null
                || placedBaseline == null || powerBaseline == null) {
            return null;
        }
        Double gap = BenchmarkRules.pairedGapPercent(placed, placedBaseline, power, powerBaseline);
        return gap == null ? null : round(gap);
    }

    /**
     * 짝짓기 문장에 함께 붙는 격차 열의 단서. 이 문장이 없으면 <b>같은 셀 안에서 응답이 모순으로 읽힌다</b> —
     * 총량 열은 "비교할 수 없다"고 적혀 있는데 격차 열에는 값이 있기 때문이다. 두 열의 짝 조건이 다르다는
     * 사실은 지표 목록과 API 설명에만 있었고, 셀만 읽는 사람에게는 닿지 않았다.
     */
    private static final String GAP_PAIRING_NOTE =
            "총전력 격차 열은 이 행과 기준 전략 둘의 건수만 맞으면 내므로 이 셀에서도 값이 있을 수 있다";

    /**
     * 총량·온도 분포 지표를 몇 곳에서 냈는지 밝히는 문장. 셀에 실어 두면 "몇 곳의 중앙값" 이라는 사실이 숫자와
     * 같은 자리에서 읽힌다 — 각주로 밀어 두면 인용될 때 떨어져 나간다.
     */
    private static String pairingNote(int usable, int total, Map<String, Integer> mismatchExample) {
        if (usable == 0) {
            return "전 전략의 배치 건수가 같은 시각 지점이 없어 총량과 온도 분포를 비교할 수 없다 — "
                    + BenchmarkRules.totalsIncomparableReason(mismatchExample) + ". " + GAP_PAIRING_NOTE;
        }
        return String.format(
                "시각 지점 %d곳 중 %d곳에서만 전 전략의 배치 건수가 같아 총량·온도 분포 지표는 그 %d곳으로 냈다 "
                        + "(짝이 아닌 지점의 예: %s). %s",
                total, usable, usable, BenchmarkRules.totalsIncomparableReason(mismatchExample),
                GAP_PAIRING_NOTE);
    }

    /** 지표 값 목록 — 같은 패키지의 테스트가 직접 부른다. */
    static List<MetricValue> metricValues(Map<String, List<Double>> samples, Placement any,
                                                  int usablePoints, List<PointOutcome> points, int droppedPoints) {
        int measuredNodes = points.get(0).measuredNodeCount();
        String strategyId = any.strategy().id();
        boolean placedAnywhere = points.stream()
                .anyMatch(p -> p.byStrategy().get(strategyId).placed() > 0);
        boolean baselinePresent = points.get(0).byStrategy().containsKey(PABFD_ID);
        List<MetricValue> values = new ArrayList<>(BenchmarkRules.METRICS.size());

        for (BenchmarkMetric metric : BenchmarkRules.METRICS) {
            List<Double> raw = samples.getOrDefault(metric.key(), List.of());
            List<Double> series = raw.stream().filter(Objects::nonNull).toList();
            // 지점별 값은 지점 대조가 필요한 지표만 싣는다. 필터 전 리스트라 인덱스가 시각 지점이고,
            // 짝이 아닌 지점은 null 로 남는다.
            List<Double> pointValues = BenchmarkRules.GAP_VS_PABFD.equals(metric.key())
                    ? pointSeries(raw, points.size()) : null;

            String invalid = invalidReason(metric, any, usablePoints, measuredNodes, placedAnywhere,
                    baselinePresent, !series.isEmpty());
            if (series.isEmpty()) {
                boolean totals = BenchmarkRules.GENERATOR_POWER.equals(metric.key()) || BenchmarkRules.ACTIVE_NODE_DELTA.equals(metric.key())
                        || BenchmarkRules.WARN_ZONE_NODES.equals(metric.key()) || BenchmarkRules.TEMP_STDDEV.equals(metric.key());
                if (invalid == null && totals && droppedPoints > 0 && droppedPoints == usablePoints) {
                    invalid = "기준 집합과 배치 건수가 다른 지점뿐이라 총량을 낼 수 없다(제외 " + droppedPoints + "곳)";
                }
                values.add(new MetricValue(metric.key(), null, null, null, 0, pointValues, false,
                        invalid != null ? invalid : "이 셀에서 값이 나오지 않았다"));
                continue;
            }
            if (invalid != null) {
                // 순위 근거가 될 수 없다고 판정한 지표에 중앙값을 실으면, valid 플래그를 보지 않은
                // 인용자에게는 그냥 숫자로 읽힌다. 값은 지우고 표본 수는 남긴다 — "지점이 없어 무효"와
                // "지점은 있었지만 판정이 무효"가 응답에서 구분되어야 한다.
                values.add(new MetricValue(metric.key(), null, null, null, series.size(), pointValues,
                        false, invalid));
                continue;
            }
            values.add(new MetricValue(metric.key(), median(series),
                    round(series.stream().mapToDouble(Double::doubleValue).min().orElseThrow()),
                    round(series.stream().mapToDouble(Double::doubleValue).max().orElseThrow()),
                    series.size(), pointValues, true, null));
        }
        return values;
    }

    /**
     * 지점별 값 — 길이는 <b>항상</b> 스윕 지점 수이고 인덱스가 시각 지점이다.
     *
     * <p>지점 루프가 지표마다 지점당 한 번씩 담으므로 운영 경로에서는 이미 길이가 맞는다 — 길이를 채우는
     * 가지는 <b>방어</b>이고, 지금은 테스트가 직접 부를 때만 지난다. 어떤 키가 조건부로만 담기게 되는 날
     * 길이 불변식이 조용히 깨지는 것을 막는 자리다. 값만 모아 압축하면 두 실행을 지점별로 대조할 때 정렬이 어긋나 짝 비교가
     * 성립하지 않는다 — 짝이 아닌 지점이 몇 번째였는지가 응답에 남아야 한다.</p>
     *
     * <p>응답 수준 테스트는 빈 자리 뒤에 값이 남는 행이 있는지만 센다. 빈 자리가 제자리에 남는지는 같은 패키지의 테스트가
     * 이 메서드를 직접 불러 확인한다 — 길이·표본 수·중앙값은 값을 앞으로 당겨 모아도 그대로라 그 셋으로는 잡지 못한다.</p>
     */
    static List<Double> pointSeries(List<Double> raw, int sweepPoints) {
        List<Double> series = new ArrayList<>(Math.max(raw.size(), sweepPoints));
        series.addAll(raw);
        while (series.size() < sweepPoints) series.add(null);
        return series;
    }

    /**
     * 지표가 이 전략·이 셀에서 순위 근거가 될 수 없는 사유. 없으면 null.
     *
     * <p>총량 계열은 <b>짝지어진 지점이 하나도 없을 때만</b> 무효다. 일부 지점만 짝이었다면 그 지점들의
     * 중앙값은 정당한 비교이고, 몇 곳이었는지는 {@code samples} 와 셀의 짝짓기 문장이 밝힌다.</p>
     *
     * <p>건당 전력은 <b>전 시각 지점에서 배치가 0건일 때만</b> 무효다. 첫 지점 하나로 판정하면 t=0 에
     * 0건이고 다른 지점에 배치한 전략의 정당한 중앙값이 통째로 사라진다 — 그래서 {@code placedAnywhere}
     * 는 셀 전체를 보고 온다. 온도 게이트·계측 노드 수는 셀 안에서 상수라 첫 지점으로 판정해도 된다.</p>
     *
     * <p>같은 패키지의 테스트가 직접 부른다 — 등록된 판의 응답에는 나오지 않는 사유가 있어 응답만으로는 지킬 수 없다.</p>
     */
    static String invalidReason(BenchmarkMetric metric, Placement any,
                                        int usablePoints, int measuredNodes, boolean placedAnywhere,
                                        boolean baselinePresent, boolean paired) {
        String key = metric.key();
        if (BenchmarkRules.GAP_VS_PABFD.equals(key)) {
            // 대조군 집합을 갈아 끼운 실행에는 기준 열이 없을 수 있다. 그때 0 이나 빈 값을 내면 "격차가
            // 없다"로 읽히므로, 전 행이 사유를 달고 무효로 나간다.
            if (!baselinePresent) {
                return "기준 전략(전력 인지 최적적합)이 이 실행에 없다";
            }
            if (PABFD_ID.equals(any.strategy().id())) {
                return "기준 전략 자신 — 0 은 값이 아니다";
            }
            // 같은 행의 총량 열은 전용 사유를 다는데 격차만 "값이 나오지 않았다"로 나가면 원인을 되짚을 수
            // 없다. 이 열이 비는 이유는 전 전략이 아니라 기준 전략 하나와 어긋났다는 것이다.
            if (!paired) {
                return "기준 전략과 배치 건수가 같은 시각 지점이 없다";
            }
        }
        if ((BenchmarkRules.GENERATOR_POWER.equals(key) || BenchmarkRules.ACTIVE_NODE_DELTA.equals(key))
                && usablePoints == 0) {
            return "기본 설정 열 전부의 배치 건수가 같은 시각 지점이 없다 — 적게 배치하고 전력을 덜 쓰는 것은 승리가 아니다";
        }
        if ((BenchmarkRules.TEMP_STDDEV.equals(key) || BenchmarkRules.WARN_ZONE_NODES.equals(key))
                && usablePoints == 0) {
            return "기본 설정 열 전부의 배치 건수가 같은 시각 지점이 없다 — 적게 배치해 CPU 를 덜 얹은 노드가 덜 뜨거운 것은 "
                    + "열 성능이 아니다";
        }
        if (BenchmarkRules.PER_WORKLOAD_POWER.equals(key) && !placedAnywhere) {
            return "전 시각 지점에서 배치 0건이라 건당 값을 낼 수 없다";
        }
        if (BenchmarkRules.C3_INTERVENTIONS.equals(key) && !any.temperatureGated()) {
            return "이 전략은 온도 제약이 꺼져 있어 항상 0 이다 — 켜진 전략과 비교하지 않는다";
        }
        // 제약을 끈 변종은 막히는 자리가 없어 성공률이 정의상 최고가 된다. 개입 횟수(위)에만 가드를 걸고
        // 성공률에 안 걸면, 반칙으로 나빠 보이는 칸만 가리고 반칙으로 좋아 보이는 칸은 그대로 두는 셈이다.
        // 온도·전력 게이트를 따로 본다 — 둘 중 하나만 끈 변종이 생기면 온도 플래그 하나로는 못 걸러진다.
        if (BenchmarkRules.PLACEMENT_RATE.equals(key) && !(any.temperatureGated() && any.powerGated())) {
            return "이 전략은 온도·전력 제약이 꺼져 있어 막히는 자리가 없다 — 제약을 받는 전략과 성공률을 "
                    + "비교하지 않는다. 이 열은 경쟁자가 아니라 '제약이 없었다면 얼마나 배치됐을까'의 천장이다";
        }
        if ((BenchmarkRules.TEMP_STDDEV.equals(key) || BenchmarkRules.WARN_ZONE_NODES.equals(key))
                && measuredNodes < 2) {
            return "계측 노드가 둘 미만이라 온도 분포를 말할 수 없다";
        }
        return null;
    }

    /**
     * 생성식 기준 총전력.
     *
     * <p>{@code AVAILABLE} 판에서 통전된 노드는 <b>배치 전부터 부하가 있던 노드와 이번에 배치한 노드</b>다 —
     * 부하가 도는 노드를 끌 수는 없으므로 "안 쓰는 노드는 꺼져 있다"를 그 노드에까지 적용하면 없는
     * 절감을 만들어 낸다.</p>
     */
    private static Double power(GroundTruthOracle oracle, PointOutcome point, Placement p, PowerControl mode) {
        return round(oracle.clusterPowerWatts(p.cpuAfterByNode(), powered(point, p), mode));
    }

    /** 배치 1건당 늘어난 생성식 기준 전력. 깨우기 비용은 {@code AVAILABLE} 판에서만 붙는다. */
    private static Double perWorkload(GroundTruthOracle oracle, PointOutcome point,
                                      Placement p, PowerControl mode) {
        if (p.placed() == 0) return null;
        double after = oracle.clusterPowerWatts(p.cpuAfterByNode(), powered(point, p), mode);
        double before = oracle.clusterPowerWatts(point.cpuBeforeByNode(), point.activeBefore(), mode);
        return round((after - before) / p.placed());
    }

    private static Set<String> powered(PointOutcome point, Placement p) {
        Set<String> on = new LinkedHashSet<>(point.activeBefore());
        on.addAll(p.usedNodes());
        return on;
    }

    private static Double spreadOf(Map<String, Double> rates) {
        if (rates.size() < 2) return null;
        double max = rates.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double min = rates.values().stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        return round(max - min);
    }

    /** 미계측 비율이 전략 간에 크게 갈리면 셀이 스스로 경고 문장을 만든다 — 각주에 의존하지 않는다. */
    private static String unmeasuredCaution(Map<String, Map<String, List<Double>>> samples,
                                            List<String> strategyIds) {
        Map<String, Double> medianRate = new LinkedHashMap<>();
        for (String id : strategyIds) {
            List<Double> series = samples.get(id)
                    .getOrDefault(BenchmarkRules.UNMEASURED_RATE, List.of())
                    .stream().filter(Objects::nonNull).toList();
            Double m = median(series);
            if (m != null) medianRate.put(id, m);
        }
        Double spread = spreadOf(medianRate);
        if (spread == null || spread < BenchmarkRules.UNMEASURED_SPREAD_CAUTION_PP) return null;

        String highest = medianRate.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
        String lowest = medianRate.entrySet().stream()
                .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
        return BenchmarkRules.unmeasuredCaution(spread, highest, lowest);
    }

    private static Map<String, Integer> probeReasonCounts(List<PointOutcome> points) {
        Map<String, Integer> counts = new TreeMap<>();
        for (PointOutcome point : points) {
            for (String reason : point.probeExclusions().values()) {
                counts.merge(shortenReason(reason), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 사유에서 숫자를 떼어 종류만 남긴다 — 숫자를 남기면 지점마다 다른 키가 되어 셀 수 없다. */
    private static String shortenReason(String reason) {
        int paren = reason.indexOf(" (");
        return paren > 0 ? reason.substring(0, paren) : reason;
    }

    private static void add(Map<String, List<Double>> into, String key, Double value) {
        into.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    /** 중앙값. 빈 목록이면 null. */
    private static Double median(List<Double> values) {
        List<Double> sorted = values.stream().filter(Objects::nonNull).sorted().toList();
        if (sorted.isEmpty()) return null;
        int mid = sorted.size() / 2;
        double m = sorted.size() % 2 == 1
                ? sorted.get(mid)
                : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
        return round(m);
    }

    /** NaN·무한대는 <b>0 이 아니라 null</b> 로 낸다 — 0 은 "가장 좋은 값"으로 읽힌다. */
    private static Double round(double value) {
        if (!Double.isFinite(value)) return null;
        return Math.round(value * 10000.0) / 10000.0;
    }
}

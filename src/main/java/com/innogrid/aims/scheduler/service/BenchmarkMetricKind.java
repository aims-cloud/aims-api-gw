package com.innogrid.aims.scheduler.service;

import java.util.List;

import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;

/**
 * 비교 지표의 <b>종류</b> — 무효 사유와 지점별 값이 지표마다 어떤 조건을 따르는지를 지표 키 하나에서 찾는다.
 *
 * <p>지표 키 문자열을 곳곳에서 따로 비교하면, 지표를 더하거나 지표가 따르는 조건을 바꿀 때 한 곳을 빠뜨려도 응답은 정상으로
 * 나가고 무효 사유만 조용히 달라진다. 그래서 metricValues · invalidReason 은 이 enum 으로 종류를 찾고, 등록되지 않은 키는
 * 조용히 넘어가지 않고 실패한다. cell() 의 지표별 표본 쌓기는 이 enum 을 쓰지 않고 지표마다 따로 조건을 건다.</p>
 *
 * <p>응답 record({@code BenchmarkMetric})와 판정 규칙({@link BenchmarkRules})은 건드리지 않는다 — 성분을 더하면 응답 바이트와
 * 규칙 축 다이제스트가 바뀐다.</p>
 */
enum BenchmarkMetricKind {

    /** 기준 전략 대비 총전력 격차 — 이 행과 기준 전략 둘의 건수만 맞으면 낸다. */
    GAP(BenchmarkRules.GAP_VS_PABFD),
    /** 짝 지점에서만 표본을 내는 총량 두 지표. */
    PAIRED_TOTAL(BenchmarkRules.GENERATOR_POWER, BenchmarkRules.ACTIVE_NODE_DELTA),
    /** 짝 지점에서만 표본을 내는 온도 분포 두 지표 — 계측 노드가 둘 이상이어야 한다. */
    PAIRED_THERMAL(BenchmarkRules.TEMP_STDDEV, BenchmarkRules.WARN_ZONE_NODES),
    /** 배치 1건당 전력 — 전 시각 지점에서 배치가 0건이면 낼 수 없다. */
    PER_WORKLOAD(BenchmarkRules.PER_WORKLOAD_POWER),
    /** 온도 제약 개입 횟수 — 온도 제약이 꺼진 전략과는 견주지 않는다. */
    THERMAL_INTERVENTIONS(BenchmarkRules.C3_INTERVENTIONS),
    /** 배치 성공률 — 온도·전력 제약 중 하나라도 꺼진 전략과는 견주지 않는다. */
    PLACEMENT_RATE(BenchmarkRules.PLACEMENT_RATE),
    /** 조건 없이 내는 지표. */
    UNCONDITIONED(BenchmarkRules.ENGINE_BELIEVED_COST, BenchmarkRules.UNMEASURED_RATE, BenchmarkRules.UNMEASURED_SPREAD);

    private final List<String> keys;

    BenchmarkMetricKind(String... keys) {
        this.keys = List.of(keys);
    }

    /** 지표 키의 종류. 등록되지 않은 키는 실패한다. */
    static BenchmarkMetricKind of(String key) {
        for (BenchmarkMetricKind kind : values()) {
            if (kind.keys.contains(key)) return kind;
        }
        throw new IllegalStateException("종류가 정해지지 않은 지표 키다: " + key
                + " — BenchmarkRules.METRICS 에 지표를 더했으면 BenchmarkMetricKind 에도 종류를 정한다");
    }

    /** 짝 지점에서만 표본을 내는 지표인지 — 짝 지점 표본이 전부 빠지면 그 사실을 사유로 적는다. */
    boolean pairedPointsOnly() {
        return this == PAIRED_TOTAL || this == PAIRED_THERMAL;
    }
}

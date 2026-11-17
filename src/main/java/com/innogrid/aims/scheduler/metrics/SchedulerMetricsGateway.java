package com.innogrid.aims.scheduler.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 스케줄러가 메트릭 저장소에 묻는 네 가지 질의.
 *
 * <p>스케줄러는 데이터를 수집하지 않고 Thanos에서 당겨 쓴다. 이 인터페이스는 그 "당겨 오는 자리"다. 기본 구현
 * {@link VictoriaMetricsGateway}는 기존 클라이언트에 그대로 위임하고, {@link SyntheticMetricsGateway}는 1세부 데이터가
 * 없는 동안 가상 클러스터로 같은 형태의 응답을 만든다. 반환 형태는 Prometheus HTTP API의 JSON을 Map/List로 푼 것과 같다.</p>
 */
public interface SchedulerMetricsGateway {

    /** 즉시 질의 원문 — {@code {"status":..., "data":{"resultType":"vector","result":[{"metric":{...},"value":[ts,"v"]}]}}}. */
    Map<String, Object> query(String promQL);

    /** 즉시 질의의 {@code data.result}만. 없으면 빈 목록. */
    List<Map<String, Object>> queryResults(String promQL);

    /** 첫 결과의 값. 없거나 못 읽으면 0 — "없음·실패·진짜 0"을 구분하지 못한다(호출자가 핵심 메트릭에는 쓰지 않는다). */
    double queryScalar(String promQL);

    /** 구간 질의의 {@code data.result} — 각 항목은 {@code metric}과 {@code values:[[ts,"v"],...]}. */
    List<Map<String, Object>> queryRangeResults(String promQL, Instant start, Instant end, String step);

    /** 어디에서 온 값인지 — {@code victoria} 또는 {@code synthetic}. */
    String source();
}

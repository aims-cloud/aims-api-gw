package com.innogrid.aims.monitoring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thanos(Prometheus 호환) 조회 결과 해석 — {@link VictoriaMetricsClient#queryResults}·{@link VictoriaMetricsClient#queryRangeResults}
 * 가 돌려주는 행을 읽는다.
 *
 * 즉시 조회 행은 {@code {"metric": {라벨}, "value": [epoch 초, "값"]}}, 범위 조회 행은 {@code "values": [[epoch 초, "값"], ...]} 이다.
 * 값이 없거나 수치가 아니면("NaN"·"+Inf" 포함) null(미수집)로 돌려준다.
 */
public final class ThanosResults {

    /** 범위 조회 표본 1개 */
    public record Sample(long epochSecond, double value) {}

    private ThanosResults() {}

    /** 라벨 1개 — 없거나 빈 값이면 null */
    public static String label(Map<String, Object> row, String name) {
        Object metric = row.get("metric");
        if (!(metric instanceof Map<?, ?> labels)) {
            return null;
        }
        Object raw = labels.get(name);
        return raw == null || raw.toString().isBlank() ? null : raw.toString();
    }

    /** 라벨 전체(읽기 전용) — metric 이 없으면 빈 맵 */
    @SuppressWarnings("unchecked")
    public static Map<String, String> labels(Map<String, Object> row) {
        Object metric = row.get("metric");
        return metric instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
    }

    /** 즉시 조회 행의 값(value[1]) */
    public static Double value(Map<String, Object> row) {
        Object value = row.get("value");
        if (value instanceof List<?> pair && pair.size() >= 2) {
            return parse(pair.get(1));
        }
        return null;
    }

    /** 즉시 조회 결과의 첫 행 값 — 결과가 없으면 null */
    public static Double firstValue(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : value(rows.get(0));
    }

    /** 즉시 조회 행의 표본 시각(value[0], epoch 초) — 없으면 null */
    public static Instant sampleTime(Map<String, Object> row) {
        Object value = row.get("value");
        if (value instanceof List<?> pair && !pair.isEmpty() && pair.get(0) instanceof Number ts) {
            return Instant.ofEpochSecond(ts.longValue());
        }
        return null;
    }

    /** 범위 조회 행의 values — 수치가 아닌 표본은 건너뛴다 */
    public static List<Sample> values(Map<String, Object> row) {
        List<Sample> out = new ArrayList<>();
        Object values = row.get("values");
        if (!(values instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof List<?> pair && pair.size() >= 2 && pair.get(0) instanceof Number ts) {
                Double v = parse(pair.get(1));
                if (v != null) {
                    out.add(new Sample(ts.longValue(), v));
                }
            }
        }
        return out;
    }

    /** 문자열·숫자 값을 수치로 — 수치가 아니거나 NaN·±Inf 면 null */
    public static Double parse(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(raw.toString());
            return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

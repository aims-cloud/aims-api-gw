package com.innogrid.aims.monitoring;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThanosResultsTest {

    private static Map<String, Object> instant(Map<String, String> labels, Object ts, Object value) {
        Map<String, Object> row = new HashMap<>();
        row.put("metric", labels);
        row.put("value", Arrays.asList(ts, value));
        return row;
    }

    @Test
    @DisplayName("label: 값이 있으면 그대로, 없거나 빈 값이거나 metric 이 없으면 null")
    void label() {
        Map<String, Object> row = instant(Map.of("instance", "10.0.0.1:9100", "empty", " "), 1_700_000_000, "1");
        assertEquals("10.0.0.1:9100", ThanosResults.label(row, "instance"));
        assertNull(ThanosResults.label(row, "empty"));
        assertNull(ThanosResults.label(row, "missing"));
        assertNull(ThanosResults.label(Map.of(), "instance"));
    }

    @Test
    @DisplayName("labels: metric 전체, 없으면 빈 맵")
    void labels() {
        Map<String, Object> row = instant(Map.of("UUID", "GPU-1", "gpu", "0"), 1_700_000_000, "1");
        assertEquals(Map.of("UUID", "GPU-1", "gpu", "0"), ThanosResults.labels(row));
        assertTrue(ThanosResults.labels(Map.of()).isEmpty());
    }

    @Test
    @DisplayName("value: 수치 문자열은 Double, NaN·±Inf·비수치·누락은 null")
    void value() {
        assertEquals(42.5, ThanosResults.value(instant(Map.of(), 1, "42.5")));
        assertEquals(3.0, ThanosResults.value(instant(Map.of(), 1, 3)));
        assertNull(ThanosResults.value(instant(Map.of(), 1, "NaN")));
        assertNull(ThanosResults.value(instant(Map.of(), 1, "+Inf")));
        assertNull(ThanosResults.value(instant(Map.of(), 1, "-Inf")));
        assertNull(ThanosResults.value(instant(Map.of(), 1, "abc")));
        assertNull(ThanosResults.value(instant(Map.of(), 1, null)));
        assertNull(ThanosResults.value(Map.of("value", List.of(1))));
        assertNull(ThanosResults.value(Map.of()));
    }

    @Test
    @DisplayName("firstValue: 첫 행의 값, 결과가 없으면 null")
    void firstValue() {
        assertEquals(7.0, ThanosResults.firstValue(List.of(instant(Map.of(), 1, "7"), instant(Map.of(), 1, "8"))));
        assertNull(ThanosResults.firstValue(List.of()));
    }

    @Test
    @DisplayName("sampleTime: value[0] 의 epoch 초, 숫자가 아니거나 없으면 null")
    void sampleTime() {
        assertEquals(Instant.ofEpochSecond(1_700_000_000L), ThanosResults.sampleTime(instant(Map.of(), 1_700_000_000.123, "1")));
        assertNull(ThanosResults.sampleTime(instant(Map.of(), "1700000000", "1")));
        assertNull(ThanosResults.sampleTime(Map.of("value", List.of())));
        assertNull(ThanosResults.sampleTime(Map.of()));
    }

    @Test
    @DisplayName("values: 범위 조회 표본을 순서대로, 비수치 값·숫자 아닌 시각·짧은 쌍은 건너뛴다")
    void values() {
        Map<String, Object> row = Map.of("values", List.of(
                List.of(100, "1.5"),
                List.of(115, "NaN"),
                List.of("130", "2"),
                List.of(145),
                List.of(160, "3")));
        assertEquals(List.of(new ThanosResults.Sample(100, 1.5), new ThanosResults.Sample(160, 3.0)), ThanosResults.values(row));
        assertTrue(ThanosResults.values(Map.of()).isEmpty());
        assertTrue(ThanosResults.values(Map.of("values", "x")).isEmpty());
    }

    @Test
    @DisplayName("parse: 숫자·문자열을 Double 로, 수치가 아니면 null")
    void parse() {
        assertEquals(0.25, ThanosResults.parse("0.25"));
        assertEquals(10.0, ThanosResults.parse(10L));
        assertNull(ThanosResults.parse(null));
        assertNull(ThanosResults.parse("NaN"));
        assertNull(ThanosResults.parse(""));
    }
}

package com.innogrid.aims.common.util;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricFormatTest {

    @Test
    @DisplayName("round1: 소수 1자리 반올림, 박싱 판은 null 을 그대로")
    void round1() {
        assertEquals(12.3, MetricFormat.round1(12.34));
        assertEquals(12.4, MetricFormat.round1(12.36));
        assertEquals(-1.2, MetricFormat.round1(-1.24));
        assertEquals(0.0, MetricFormat.round1(0.04));
        assertEquals(12.3, MetricFormat.round1(Double.valueOf(12.34)));
        assertNull(MetricFormat.round1((Double) null));
    }

    @Test
    @DisplayName("round2: 소수 2자리 반올림, 박싱 판은 null 을 그대로")
    void round2() {
        assertEquals(1.23, MetricFormat.round2(1.234));
        assertEquals(1.24, MetricFormat.round2(1.236));
        assertEquals(1.23, MetricFormat.round2(Double.valueOf(1.234)));
        assertNull(MetricFormat.round2((Double) null));
    }

    @Test
    @DisplayName("toInt: 정수 반올림, null 은 null")
    void toInt() {
        assertEquals(3, MetricFormat.toInt(2.5));
        assertEquals(2, MetricFormat.toInt(2.4));
        assertNull(MetricFormat.toInt(null));
    }

    @Test
    @DisplayName("bytesToGb: 1024³ 바이트 = 1 GB, 소수 1자리")
    void bytesToGb() {
        assertEquals(1.0, MetricFormat.bytesToGb(1024.0 * 1024 * 1024));
        assertEquals(1.5, MetricFormat.bytesToGb(1.5 * 1024 * 1024 * 1024));
        assertEquals(0.1, MetricFormat.bytesToGb(100L * 1024 * 1024));          // 0.0977 → 0.1
        assertEquals(16.0, MetricFormat.bytesToGb(Double.valueOf(16 * MetricFormat.BYTES_PER_GB)));
        assertNull(MetricFormat.bytesToGb((Double) null));
    }

    @Test
    @DisplayName("mbToGb: 1024 MB = 1 GB, 소수 1자리, null 은 null")
    void mbToGb() {
        assertEquals(15.0, MetricFormat.mbToGb(15360.0));                           // Tesla T4 VRAM
        assertEquals(0.2, MetricFormat.mbToGb(250.0));
        assertNull(MetricFormat.mbToGb(null));
    }

    @Test
    @DisplayName("toOffsetDateTime: 서버 시간대로, 같은 순간을 가리킨다")
    void toOffsetDateTime() {
        Instant instant = Instant.parse("2026-10-16T00:00:00Z");
        assertEquals(instant, MetricFormat.toOffsetDateTime(instant).toInstant());
        assertEquals(ZoneId.systemDefault().getRules().getOffset(instant), MetricFormat.toOffsetDateTime(instant).getOffset());
        assertNull(MetricFormat.toOffsetDateTime(null));
    }
}

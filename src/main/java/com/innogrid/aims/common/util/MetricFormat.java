package com.innogrid.aims.common.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 응답에 담을 지표 값을 다듬는다 — 반올림, 바이트·MB 의 GB 환산, 수집 시각의 서버 시간대 변환.
 *
 * <p>박싱 타입({@link Double}) 판은 null(미수집)을 그대로 null 로 돌려준다.
 * 수치 판({@code double})은 값이 반드시 있는 계산식에 쓴다.</p>
 *
 * <p>스프링 빈이 아니다. {@link TimeParams} 와 같은 정적 도우미다.</p>
 */
public final class MetricFormat {

    /** 1 GB(GiB) 의 바이트 수 — PromQL 에서 {@code (long) BYTES_PER_GB} 로도 쓴다 */
    public static final double BYTES_PER_GB = 1024.0 * 1024 * 1024;
    /** 1 GB 의 MB 수 — DCGM 은 VRAM 을 MB 로 준다 */
    public static final double MB_PER_GB = 1024.0;

    private MetricFormat() {}

    /** 소수 1자리 반올림 */
    public static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** 소수 1자리 반올림 — null 은 null */
    public static Double round1(Double value) {
        return value == null ? null : round1(value.doubleValue());
    }

    /** 소수 2자리 반올림 */
    public static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /** 소수 2자리 반올림 — null 은 null */
    public static Double round2(Double value) {
        return value == null ? null : round2(value.doubleValue());
    }

    /** 정수로 반올림 — null 은 null */
    public static Integer toInt(Double value) {
        return value == null ? null : (int) Math.round(value);
    }

    /** 바이트 → GB, 소수 1자리 */
    public static double bytesToGb(double bytes) {
        return round1(bytes / BYTES_PER_GB);
    }

    /** 바이트 → GB, 소수 1자리 — null 은 null */
    public static Double bytesToGb(Double bytes) {
        return bytes == null ? null : bytesToGb(bytes.doubleValue());
    }

    /** MB → GB, 소수 1자리 — null 은 null */
    public static Double mbToGb(Double mb) {
        return mb == null ? null : round1(mb / MB_PER_GB);
    }

    /** 수집 시각을 서버 시간대의 {@link OffsetDateTime} 으로 — null 은 null */
    public static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}

package com.innogrid.aims.common.util;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.*;

class TimeParamsTest {

    private static final Instant FALLBACK = Instant.ofEpochSecond(1_700_000_000L);

    @DisplayName("ISO-8601 시각을 해석한다 — Z 와 오프셋 모두")
    @Test
    void parses_iso_instant() {
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"),
                TimeParams.parseInstant("from", "2026-10-12T00:00:00Z", FALLBACK));
        assertEquals(Instant.parse("2026-10-11T15:00:00Z"),
                TimeParams.parseInstant("from", "2026-10-12T00:00:00+09:00", FALLBACK));
    }

    @DisplayName("값이 없으면 기본값을 쓴다")
    @Test
    void null_uses_fallback() {
        assertEquals(FALLBACK, TimeParams.parseInstant("to", null, FALLBACK));
    }

    @DisplayName("형식이 틀리면 파라미터 이름과 값을 담은 400 이다")
    @Test
    void invalid_is_bad_request() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> TimeParams.parseInstant("from", "not-a-date", FALLBACK));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().startsWith("from "));
        assertTrue(ex.getMessage().endsWith(": not-a-date"));

        // 오프셋 없는 로컬 시각·빈 문자열도 형식 오류
        assertThrows(BusinessException.class, () -> TimeParams.parseInstant("to", "2026-10-12T00:00:00", FALLBACK));
        assertThrows(BusinessException.class, () -> TimeParams.parseInstant("to", "", FALLBACK));
    }

    @DisplayName("오늘 시작은 서버 타임존 자정이다")
    @Test
    void start_of_today_is_local_midnight() {
        Instant start = TimeParams.startOfToday();
        assertEquals(LocalTime.MIDNIGHT, start.atZone(ZoneId.systemDefault()).toLocalTime());
        assertFalse(start.isAfter(Instant.now()));
    }
}

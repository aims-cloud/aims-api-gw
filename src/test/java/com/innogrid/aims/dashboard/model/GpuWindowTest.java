package com.innogrid.aims.dashboard.model;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import com.innogrid.aims.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

/** 조회 기간 — 기본 최근 1시간, 한쪽 생략, from ≥ to·30일 초과 400, 범위 선택자, 간격(창/60, 15초~1시간). */
class GpuWindowTest {

    Instant now = Instant.parse("2026-10-06T03:00:00Z");
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test void defaultsToLastHour() {
        GpuWindow w = GpuWindow.of(null, null, clock);
        assertThat(w.from()).isEqualTo(now.minusSeconds(3600));
        assertThat(w.to()).isEqualTo(now);
        assertThat(w.defaultWindow()).isTrue();
        assertThat(w.range()).isEqualTo("[3600s]");
        assertThat(w.step()).isEqualTo("60s");
        assertThat(w.cacheKey()).isEqualTo("default");
    }

    @Test void oneSidedInputUsesDefaultsForTheOtherSide() {
        OffsetDateTime to = OffsetDateTime.parse("2026-10-05T12:00:00Z");
        GpuWindow onlyTo = GpuWindow.of(null, to, clock);
        assertThat(onlyTo.from()).isEqualTo(to.toInstant().minusSeconds(3600));
        assertThat(onlyTo.defaultWindow()).isFalse();
        GpuWindow onlyFrom = GpuWindow.of(OffsetDateTime.parse("2026-10-06T02:30:00+00:00"), null, clock);
        assertThat(onlyFrom.to()).isEqualTo(now);
        assertThat(onlyFrom.seconds()).isEqualTo(1800);
    }

    @Test void stepIsBoundedAndInvalidWindowsAre400() {
        OffsetDateTime to = OffsetDateTime.parse("2026-10-06T00:00:00Z");
        assertThat(GpuWindow.of(to.minusMinutes(5), to, clock).step()).isEqualTo("15s");         // 5s → 최소 15s
        assertThat(GpuWindow.of(to.minusDays(30), to, clock).step()).isEqualTo("3600s");         // 12h → 최대 1h
        assertThatThrownBy(() -> GpuWindow.of(to, to, clock))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> GpuWindow.of(to.minusDays(30).minusSeconds(1), to, clock)).isInstanceOf(BusinessException.class);
    }
}

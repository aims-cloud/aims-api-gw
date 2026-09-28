package com.innogrid.aims.scheduler.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** LIKE 이스케이프 — 백슬래시를 먼저 두 겹으로 만든 뒤 {@code %}·{@code _} 를 이스케이프한다(순서가 바뀌면 이중 이스케이프). */
class WorkloadQueryServiceEscapeTest {

    @DisplayName("백슬래시를 먼저, 그다음 % 와 _ 를 이스케이프한다")
    @Test
    void escape_like_backslash_first() {
        assertEquals("a\\\\b\\%c\\_d", WorkloadQueryService.escapeLike("a\\b%c_d"));
        assertEquals("plain", WorkloadQueryService.escapeLike("plain"));
        assertEquals("", WorkloadQueryService.escapeLike(""));
    }
}

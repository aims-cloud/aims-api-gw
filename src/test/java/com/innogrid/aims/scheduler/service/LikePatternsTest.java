package com.innogrid.aims.scheduler.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingHistory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** LIKE 패턴 — 백슬래시를 먼저 두 겹으로 만든 뒤 {@code %}·{@code _} 를 이스케이프한다(순서가 바뀌면 이중 이스케이프). */
class LikePatternsTest {

    @DisplayName("백슬래시를 먼저, 그다음 % 와 _ 를 이스케이프한다")
    @Test
    void escape_backslash_first() {
        assertEquals("a\\\\b\\%c\\_d", LikePatterns.escape("a\\b%c_d"));
        assertEquals("plain", LikePatterns.escape("plain"));
        assertEquals("", LikePatterns.escape(""));
    }

    @DisplayName("부분 일치 패턴은 이스케이프한 값을 % 로 감싸고 이스케이프 문자는 백슬래시다")
    @Test
    void contains_wraps_escaped_value() {
        assertEquals("%a\\_b%", LikePatterns.contains("a_b"));
        assertEquals("%100\\%%", LikePatterns.contains("100%"));
        assertEquals("%\"COOLING\\_DOWN\":%", LikePatterns.contains(SchedulingHistory.exclusionCodeKey("COOLING_DOWN")),
                "제외 코드 키의 밑줄도 문자 그대로 — _ 가 임의 한 글자로 맞지 않는다");
        assertEquals('\\', LikePatterns.ESCAPE);
    }
}

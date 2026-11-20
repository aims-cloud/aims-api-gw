package com.innogrid.aims.scheduler.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MDC 쌍 정규화 — 순서에 상관없이 같은 쌍은 같은 행이 되어야 한다.
 */
class MdcLinkTest {

    @DisplayName("쌍은 사전순으로 정렬되어 저장된다")
    @Test
    void pair_stored_sorted() {
        MdcLink reversed = new MdcLink("mdc-02", "mdc-01", 10);
        MdcLink ordered = new MdcLink("mdc-01", "mdc-02", 10);

        assertEquals("mdc-01", reversed.getMdcA());
        assertEquals("mdc-02", reversed.getMdcB());
        assertEquals(ordered.pair(), reversed.pair(), "역방향으로 넣어도 같은 쌍");
    }

    @DisplayName("같은 MDC 둘은 링크가 아니다")
    @Test
    void same_mdc_is_not_a_link() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MdcLink.Pair.of("mdc-01", "mdc-01"));
        assertTrue(e.getMessage().contains("같은 MDC"));
        assertThrows(IllegalArgumentException.class, () -> new MdcLink("x", "x", 1));
    }

    @DisplayName("걸린 MDC 와 반대편을 알려준다")
    @Test
    void link_reports_other_side() {
        MdcLink link = new MdcLink("seoul", "busan", 20);

        assertTrue(link.connects("seoul"));
        assertTrue(link.connects("busan"));
        assertFalse(link.connects("daegu"));
        assertEquals("seoul", link.other("busan"));
        assertEquals("busan", link.other("seoul"));
        assertNull(link.other("daegu"));

        link.repair("daegu", "busan");
        assertEquals("busan", link.getMdcA());
        assertEquals("daegu", link.getMdcB());
        assertFalse(link.connects("seoul"));
    }
}

package com.innogrid.aims.scheduler.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MDC 쌍 정규화 — 순서에 상관없이 같은 쌍은 같은 행이 되어야 한다.
 */
class MdcLinkTest {

    @Test
    void 쌍은_사전순으로_정렬되어_저장된다() {
        MdcLink reversed = new MdcLink("mdc-02", "mdc-01", 10);
        MdcLink ordered = new MdcLink("mdc-01", "mdc-02", 10);

        assertEquals("mdc-01", reversed.getMdcA());
        assertEquals("mdc-02", reversed.getMdcB());
        assertEquals(ordered.pair(), reversed.pair(), "역방향으로 넣어도 같은 쌍");
    }

    @Test
    void 같은_MDC_둘은_링크가_아니다() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MdcLink.Pair.of("mdc-01", "mdc-01"));
        assertTrue(e.getMessage().contains("같은 MDC"));
        assertThrows(IllegalArgumentException.class, () -> new MdcLink("x", "x", 1));
    }

    @Test
    void 걸린_MDC_와_반대편을_알려준다() {
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

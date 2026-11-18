package com.innogrid.aims.scheduler.model;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.MdcLink;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 링크 스냅샷 — 방향 없는 조회, 같은 MDC·미등록·null은 비어 있음. 해석은 호출자 몫이다(제약은 통과, 점수는
 * 같은 MDC 1.0, 미등록 기본 0.5).
 */
class LinkMatrixTest {

    @DisplayName("어느 방향으로 물어도 같은 링크가 나온다")
    @Test
    void either_direction_same_link() {
        MdcLink l = new MdcLink("b", "a", 20);
        l.setStatus(MdcLink.LinkStatus.DEGRADED);
        LinkMatrix m = LinkMatrix.of(List.of(l));

        assertEquals(20.0, m.between("a", "b").orElseThrow().latencyMs());
        assertEquals(MdcLink.LinkStatus.DEGRADED, m.between("b", "a").orElseThrow().status());
        assertEquals(1, m.size());
    }

    @DisplayName("같은 MDC 미등록 null 은 비어 있다 — 구분은 호출자가 한다")
    @Test
    void same_mdc_and_null_are_empty() {
        LinkMatrix m = LinkMatrix.of(List.of(new MdcLink("a", "b", 1)));

        assertTrue(m.between("a", "a").isEmpty(), "같은 MDC");
        assertTrue(m.between("a", "c").isEmpty(), "미등록");
        assertTrue(m.between(null, "a").isEmpty(), "null");
        assertTrue(m.between("a", "unknown").isEmpty(), "라벨 없는 노드도 그냥 미등록");
    }

    @DisplayName("빈 원장은 EMPTY 이고 손실 null 은 0 으로 본다")
    @Test
    void empty_ledger_null_loss_0() {
        assertSame(LinkMatrix.EMPTY, LinkMatrix.of(List.of()));
        assertSame(LinkMatrix.EMPTY, LinkMatrix.of(null));
        assertTrue(LinkMatrix.EMPTY.isEmpty());

        LinkMatrix.Quality q = new LinkMatrix.Quality(5, null, null, MdcLink.LinkStatus.UP);
        assertEquals(0.0, q.lossOrZero());
        assertNull(q.bandwidthMbps(), "대역폭도 모르면 null — 부족분 항이 0 이 된다");
        assertFalse(q.isDown());
        assertTrue(new LinkMatrix.Quality(5, null, 0.0, MdcLink.LinkStatus.DOWN).isDown());
    }
}

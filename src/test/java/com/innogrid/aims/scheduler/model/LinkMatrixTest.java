package com.innogrid.aims.scheduler.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.MdcLink;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 링크 스냅샷 — 방향 없는 조회, 같은 MDC·미등록·null 은 비어 있음(중립).
 */
class LinkMatrixTest {

    @Test
    void 어느_방향으로_물어도_같은_링크가_나온다() {
        MdcLink l = new MdcLink("b", "a", 20);
        l.setStatus(MdcLink.LinkStatus.DEGRADED);
        LinkMatrix m = LinkMatrix.of(List.of(l));

        assertEquals(20.0, m.between("a", "b").orElseThrow().latencyMs());
        assertEquals(MdcLink.LinkStatus.DEGRADED, m.between("b", "a").orElseThrow().status());
        assertEquals(1, m.size());
    }

    @Test
    void 같은_MDC_미등록_null_은_비어_있다_중립() {
        LinkMatrix m = LinkMatrix.of(List.of(new MdcLink("a", "b", 1)));

        assertTrue(m.between("a", "a").isEmpty(), "같은 MDC");
        assertTrue(m.between("a", "c").isEmpty(), "미등록");
        assertTrue(m.between(null, "a").isEmpty(), "null");
        assertTrue(m.between("a", "unknown").isEmpty(), "라벨 없는 노드도 그냥 미등록");
    }

    @Test
    void 빈_원장은_EMPTY_이고_손실_null_은_0_으로_본다() {
        assertSame(LinkMatrix.EMPTY, LinkMatrix.of(List.of()));
        assertSame(LinkMatrix.EMPTY, LinkMatrix.of(null));
        assertTrue(LinkMatrix.EMPTY.isEmpty());

        LinkMatrix.Quality q = new LinkMatrix.Quality(5, null, MdcLink.LinkStatus.UP);
        assertEquals(0.0, q.lossOrZero());
        assertFalse(q.isDown());
        assertTrue(new LinkMatrix.Quality(5, 0.0, MdcLink.LinkStatus.DOWN).isDown());
    }
}

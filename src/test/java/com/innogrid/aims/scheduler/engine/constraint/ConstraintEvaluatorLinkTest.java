package com.innogrid.aims.scheduler.engine.constraint;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.dto.ConstraintDescriptionResponse;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C5 MDC 링크 — DOWN 만 제외, 나머지는 통과, 게이트가 꺼지면 아무것도 하지 않는다.
 */
class ConstraintEvaluatorLinkTest {

    private final ConstraintEvaluator evaluator = ConstraintEvaluator.withDefaults();

    private static final LinkMatrix LINKS = LinkMatrix.of(List.of(
            link("data", "up", 20, MdcLink.LinkStatus.UP),
            link("data", "degraded", 20, MdcLink.LinkStatus.DEGRADED),
            link("data", "down", 5, MdcLink.LinkStatus.DOWN)));

    @Test
    void DOWN_링크의_노드는_사유와_함께_제외한다() {
        String reason = evaluator.exclusionReason(workload("data"), node("down"), ctx(true));

        assertNotNull(reason);
        assertTrue(reason.contains("MDC 링크 단절"), reason);
        assertTrue(reason.contains("data ↔ down DOWN"), reason);
    }

    @Test
    void UP_과_DEGRADED_는_제약이_아니라_점수의_영역이라_통과한다() {
        assertNull(evaluator.exclusionReason(workload("data"), node("up"), ctx(true)));
        assertNull(evaluator.exclusionReason(workload("data"), node("degraded"), ctx(true)));
    }

    @Test
    void 같은_MDC_미등록_쌍_라벨_없는_노드는_통과한다() {
        assertNull(evaluator.exclusionReason(workload("data"), node("data"), ctx(true)), "같은 MDC");
        assertNull(evaluator.exclusionReason(workload("data"), node("elsewhere"), ctx(true)), "미등록");
        assertNull(evaluator.exclusionReason(workload("data"), node("unknown"), ctx(true)), "라벨 없음도 미등록으로 중립");
        assertNull(evaluator.exclusionReason(workload("data"), node(null), ctx(true)), "MDC null");
    }

    @Test
    void 게이트가_꺼져_있으면_DOWN_이어도_통과한다() {
        assertNull(evaluator.exclusionReason(workload("data"), node("down"), ctx(false)), "정책 off");
        assertNull(evaluator.exclusionReason(workload(null), node("down"), ctx(true)), "dataMdcId 없음");
    }

    @Test
    void 링크_스냅샷이_비어_있으면_켜져_있어도_통과한다() {
        PlacementContext empty = PlacementContext.of(List.of(node("down")), policy(true));
        assertNull(evaluator.exclusionReason(workload("data"), node("down"), empty));
    }

    @Test
    void 제약_설명에_C5_가_있고_순서가_마지막이다() {
        List<ConstraintDescriptionResponse> all = ConstraintDescriptionResponse.all();
        assertEquals(6, all.size());
        ConstraintDescriptionResponse c5 = all.get(5);
        assertEquals("C5", c5.code());
        assertTrue(c5.togglable());
        assertTrue(c5.description().contains("affinityEnabled"));
        assertTrue(c5.description().contains("DOWN"));
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private static MdcLink link(String a, String b, double latency, MdcLink.LinkStatus st) {
        MdcLink l = new MdcLink(a, b, latency);
        l.setStatus(st);
        return l;
    }

    private static SchedulingPolicy policy(boolean affinity) {
        SchedulingPolicy p = new SchedulingPolicy("p", "d");
        p.setAffinityEnabled(affinity);
        return p;
    }

    private static PlacementContext ctx(boolean affinity) {
        return PlacementContext.of(List.of(node("up"), node("degraded"), node("down")), policy(affinity))
                .withLinkMatrix(LINKS);
    }

    private static Workload workload(String dataMdcId) {
        Workload w = new Workload("w", 1, 512);
        w.setDataMdcId(dataMdcId);
        return w;
    }

    private static NodeResource node(String mdcId) {
        return new NodeResource(mdcId, "n-" + mdcId, 8, 16384, 0, 10, 10, 0, 0, 0, 0, 0, 0);
    }
}

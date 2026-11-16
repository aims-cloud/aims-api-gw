package com.innogrid.aims.scheduler.engine.constraint;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.dto.ConstraintDescriptionResponse;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.NodeResource;

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

    @DisplayName("DOWN 링크의 노드는 사유와 함께 제외한다")
    @Test
    void link_down_excluded_with_reason() {
        String reason = evaluator.exclusionReason(workload("data"), node("down"), ctx(true));

        assertNotNull(reason);
        assertTrue(reason.contains("MDC 링크 단절"), reason);
        assertTrue(reason.contains("data ↔ down DOWN"), reason);
    }

    @DisplayName("UP 과 DEGRADED 는 제약이 아니라 점수의 영역이라 통과한다")
    @Test
    void up_and_degraded_pass() {
        assertNull(evaluator.exclusionReason(workload("data"), node("up"), ctx(true)));
        assertNull(evaluator.exclusionReason(workload("data"), node("degraded"), ctx(true)));
    }

    @DisplayName("같은 MDC 미등록 쌍 라벨 없는 노드는 통과한다")
    @Test
    void same_mdc_and_unlabeled_pass() {
        assertNull(evaluator.exclusionReason(workload("data"), node("data"), ctx(true)), "같은 MDC");
        assertNull(evaluator.exclusionReason(workload("data"), node("elsewhere"), ctx(true)), "미등록");
        assertNull(evaluator.exclusionReason(workload("data"), node("unknown"), ctx(true)), "라벨 없음도 미등록처럼 통과");
        assertNull(evaluator.exclusionReason(workload("data"), node(null), ctx(true)), "MDC null");
    }

    @DisplayName("게이트가 꺼져 있으면 DOWN 이어도 통과한다")
    @Test
    void gate_off_passes_link_down() {
        assertNull(evaluator.exclusionReason(workload("data"), node("down"), ctx(false)), "정책 off");
        assertNull(evaluator.exclusionReason(workload(null), node("down"), ctx(true)), "dataMdcId 없음");
    }

    @DisplayName("링크 스냅샷이 비어 있으면 켜져 있어도 통과한다")
    @Test
    void empty_snapshot_passes_gate_on() {
        PlacementContext empty = PlacementContext.of(List.of(node("down")), policy(true));
        assertNull(evaluator.exclusionReason(workload("data"), node("down"), empty));
    }

    @DisplayName("제약 설명에 C5 가 있고 전력(C4) 다음 자리다")
    @Test
    void c5_listed_after_power() {
        List<ConstraintDescriptionResponse> all = ConstraintDescriptionResponse.all();
        assertEquals(7, all.size());   // C6 MDC 전력 예산이 뒤에 붙어 C5 는 더 이상 마지막이 아니다
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

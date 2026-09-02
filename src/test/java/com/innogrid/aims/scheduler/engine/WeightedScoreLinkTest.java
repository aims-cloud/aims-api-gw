package com.innogrid.aims.scheduler.engine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * weighted 엔진의 네트워크 점수가 게이트 on 에서만 링크 품질을 섞는가.
 *
 * <p>자원이 같은 노드 4대(MDC a·b·c·d), 데이터는 a 에. 링크 a-b 20ms UP(0.714) · a-c 20ms DEGRADED(0.588) ·
 * a-d DOWN. 게이트 off 면 4대 점수가 같고 {@code 링크품질} 키가 없다. 게이트 on 이면 a > b > c 로 갈리고
 * d 는 C5 로 제외된다. 트래픽이 0 이라 local=1.0, linkWeight 0.5 → net = 0.5 + 0.5×link.</p>
 */
class WeightedScoreLinkTest {

    private static final double EPS = 1e-3;
    private final WeightedScoreScheduler scheduler = new WeightedScoreScheduler();

    private static final LinkMatrix LINKS = LinkMatrix.of(List.of(
            link("a", "b", 20, MdcLink.LinkStatus.UP),
            link("a", "c", 20, MdcLink.LinkStatus.DEGRADED),
            link("a", "d", 5, MdcLink.LinkStatus.DOWN)));

    @Test
    void 게이트가_꺼지면_점수가_전부_같고_링크품질_키가_없다() {
        SchedulingResult r = scheduler.schedule(workload("a"), ctx(false));
        Map<String, NodeScore> by = byMdc(r);

        assertEquals(4, r.candidateScores().size());
        assertTrue(by.values().stream().allMatch(NodeScore::eligible), "DOWN 도 게이트 off 면 제외되지 않는다");
        double first = by.get("a").totalScore();
        assertTrue(by.values().stream().allMatch(s -> Math.abs(s.totalScore() - first) < EPS), "4대 동일");
        assertTrue(by.values().stream().noneMatch(s -> s.details().containsKey("링크품질")), "기존 응답 키 불변");
        assertEquals(1.0, by.get("b").networkScore(), EPS);
    }

    @Test
    void 게이트가_켜지면_같은_MDC_UP_DEGRADED_순으로_갈리고_DOWN_은_제외된다() {
        SchedulingResult r = scheduler.schedule(workload("a"), ctx(true));
        Map<String, NodeScore> by = byMdc(r);

        assertTrue(r.success());
        assertEquals("a", r.selectedMdcId(), "데이터가 있는 MDC 가 1순위");
        assertEquals(1.0, by.get("a").details().get("링크품질"), EPS);
        assertEquals(0.714, by.get("b").details().get("링크품질"), EPS);
        assertEquals(0.588, by.get("c").details().get("링크품질"), EPS);
        assertEquals(0.857, by.get("b").networkScore(), EPS, "0.5×1.0 + 0.5×0.714");
        assertTrue(by.get("a").totalScore() > by.get("b").totalScore());
        assertTrue(by.get("b").totalScore() > by.get("c").totalScore());
        assertFalse(by.get("d").eligible());
        assertTrue(by.get("d").exclusionReason().contains("MDC 링크 단절"));
    }

    @Test
    void dataMdcId_가_없는_워크로드는_켠_정책에서도_예전_점수다() {
        SchedulingResult r = scheduler.schedule(new Workload("w", 1, 512), ctx(true));
        Map<String, NodeScore> by = byMdc(r);

        assertTrue(by.values().stream().noneMatch(s -> s.details().containsKey("링크품질")));
        assertTrue(by.get("d").eligible());
        assertEquals(1.0, by.get("c").networkScore(), EPS);
    }

    @Test
    void 미등록_MDC_의_노드는_중립_1_0_으로_같은_MDC_와_동점이다() {
        Workload w = workload("z");   // z 는 원장에 없다
        SchedulingResult r = scheduler.schedule(w, ctx(true));
        Map<String, NodeScore> by = byMdc(r);

        assertTrue(by.values().stream().allMatch(s -> Math.abs(s.details().get("링크품질") - 1.0) < EPS));
        assertTrue(by.values().stream().allMatch(NodeScore::eligible));
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private static MdcLink link(String a, String b, double latency, MdcLink.LinkStatus st) {
        MdcLink l = new MdcLink(a, b, latency);
        l.setStatus(st);
        return l;
    }

    private static PlacementContext ctx(boolean affinity) {
        SchedulingPolicy p = new SchedulingPolicy("p", "d");
        p.setAffinityEnabled(affinity);
        return PlacementContext.of(List.of(node("a"), node("b"), node("c"), node("d")), p).withLinkMatrix(LINKS);
    }

    private static Workload workload(String dataMdcId) {
        Workload w = new Workload("w", 1, 512);
        w.setDataMdcId(dataMdcId);
        return w;
    }

    private static NodeResource node(String mdcId) {
        return new NodeResource(mdcId, "n-" + mdcId, 8, 16384, 0, 10, 10, 0, 0, 0, 0, 0, 0);
    }

    private static Map<String, NodeScore> byMdc(SchedulingResult r) {
        return r.candidateScores().stream().collect(Collectors.toMap(NodeScore::mdcId, Function.identity()));
    }
}

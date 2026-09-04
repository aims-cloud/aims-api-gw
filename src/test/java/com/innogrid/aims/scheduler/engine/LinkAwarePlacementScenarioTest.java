package com.innogrid.aims.scheduler.engine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
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
 * 링크 반영 시나리오 — 데이터 위치에 따라 배치가 어디로 가는지, 게이트를 끄면 예전과 같은지, 세 엔진이 C5 를 공유하는지.
 *
 * <pre>
 * 클러스터  MDC a{n-a} · b{n-b} · c{n-c} · d{n-d}  자원·부하 동일(트래픽 0)
 * 링크      a-b 20ms UP · a-c 20ms DEGRADED · a-d 5ms DOWN · b-c 40ms UP        (b-d, c-d 미등록)
 * 정책      affinityEnabled=true, 가중치 기본(cpu .25 mem .25 pwr .2 temp .15 net .15)
 *
 * ① data=a, 게이트 off   → 4대 동점 · 링크품질 키 없음 · d 후보 유지                (현행과 동일)
 * ② data=a, 게이트 on    → a(1.0) > b(0.714) > c(0.588) · d 제외 "MDC 링크 단절"
 * ③ data=b, 게이트 on    → b(1.0) > a(0.714) > c(40ms UP 0.556) · d 미등록 → 중립 1.0 로 b 와 동점
 * ④ data=a, a 만석       → b 선택 (a 는 C1 제외, d 는 C5 제외)
 * ⑤ data=a, power 엔진   → d 제외(C5 공유), 나머지 점수는 링크 무관(power 식)
 * ⑥ data=a, temperature  → d 제외(C5 공유)
 * </pre>
 */
class LinkAwarePlacementScenarioTest {

    private static final double EPS = 1e-3;

    private final WeightedScoreScheduler weighted = new WeightedScoreScheduler();
    private final PowerAwareScheduler power = new PowerAwareScheduler();
    private final TemperatureAwareScheduler temperature = new TemperatureAwareScheduler();

    private static final LinkMatrix LINKS = LinkMatrix.of(List.of(
            link("a", "b", 20, MdcLink.LinkStatus.UP),
            link("a", "c", 20, MdcLink.LinkStatus.DEGRADED),
            link("a", "d", 5, MdcLink.LinkStatus.DOWN),
            link("b", "c", 40, MdcLink.LinkStatus.UP)));

    @DisplayName("시나리오 데이터 위치와 링크 품질에 따라 배치가 갈리고 게이트를 끄면 현행과 같다")
    @Test
    void scenario_link_quality_placement() {
        StringBuilder table = new StringBuilder("\n| # | 상황 | a | b | c | d | 선택 |\n|---|---|---|---|---|---|---|\n");

        // ① 게이트 off
        SchedulingResult r1 = weighted.schedule(workload("a"), ctx(false, nodes()));
        Map<String, NodeScore> s1 = byMdc(r1);
        row(table, "①", "data=a 게이트 off", s1, r1);
        double base = s1.get("a").totalScore();
        assertTrue(s1.values().stream().allMatch(s -> Math.abs(s.totalScore() - base) < EPS), "① 4대 동점");
        assertTrue(s1.values().stream().noneMatch(s -> s.details().containsKey("링크품질")), "① 키 없음");
        assertTrue(s1.get("d").eligible(), "① d 후보 유지");

        // ② 게이트 on, data=a
        SchedulingResult r2 = weighted.schedule(workload("a"), ctx(true, nodes()));
        Map<String, NodeScore> s2 = byMdc(r2);
        row(table, "②", "data=a 게이트 on", s2, r2);
        assertEquals("a", r2.selectedMdcId());
        assertEquals(1.0, s2.get("a").details().get("링크품질"), EPS);
        assertEquals(0.714, s2.get("b").details().get("링크품질"), EPS);
        assertEquals(0.588, s2.get("c").details().get("링크품질"), EPS);
        assertFalse(s2.get("d").eligible());
        assertTrue(s2.get("d").exclusionReason().contains("MDC 링크 단절"));
        assertEquals(base, s2.get("a").totalScore(), EPS, "② 같은 MDC 노드 점수는 게이트 off 와 동일(링크 1.0)");

        // ③ 게이트 on, data=b
        SchedulingResult r3 = weighted.schedule(workload("b"), ctx(true, nodes()));
        Map<String, NodeScore> s3 = byMdc(r3);
        row(table, "③", "data=b 게이트 on", s3, r3);
        assertEquals("b", r3.selectedMdcId());
        assertEquals(0.714, s3.get("a").details().get("링크품질"), EPS);
        assertEquals(0.556, s3.get("c").details().get("링크품질"), EPS, "40ms UP → 1/(1+0.8)");
        assertEquals(1.0, s3.get("d").details().get("링크품질"), EPS, "b-d 미등록 → 중립");
        assertTrue(s3.get("d").eligible(), "미등록은 제외하지 않는다");

        // ④ data=a, a 만석 → b
        List<NodeResource> full = nodes();
        full.get(0).reserve(8, 16384, 0, 0);   // n-a 자원 소진
        SchedulingResult r4 = weighted.schedule(workload("a"), ctx(true, full));
        Map<String, NodeScore> s4 = byMdc(r4);
        row(table, "④", "data=a, a 만석", s4, r4);
        assertEquals("b", r4.selectedMdcId());
        assertFalse(s4.get("a").eligible());
        assertFalse(s4.get("d").eligible());

        // ⑤⑥ power · temperature — C5 공유, 점수는 링크 무관
        SchedulingResult r5 = power.schedule(workload("a"), ctx(true, nodes()));
        Map<String, NodeScore> s5 = byMdc(r5);
        row(table, "⑤", "data=a power 엔진", s5, r5);
        assertFalse(s5.get("d").eligible());
        assertTrue(s5.get("d").exclusionReason().contains("MDC 링크 단절"));
        assertTrue(s5.values().stream().noneMatch(s -> s.details().containsKey("링크품질")), "power 는 점수 혼합 없음");
        assertEquals(s5.get("b").totalScore(), s5.get("c").totalScore(), EPS, "power 점수는 링크와 무관");

        SchedulingResult r6 = temperature.schedule(workload("a"), ctx(true, nodes()));
        Map<String, NodeScore> s6 = byMdc(r6);
        row(table, "⑥", "data=a temperature 엔진", s6, r6);
        assertFalse(s6.get("d").eligible());
        assertTrue(s6.get("d").exclusionReason().contains("MDC 링크 단절"));

        System.out.println(table);
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private static void row(StringBuilder t, String no, String label, Map<String, NodeScore> by, SchedulingResult r) {
        t.append(String.format("| %s | %s | %s | %s | %s | %s | %s |%n", no, label,
                cell(by.get("a")), cell(by.get("b")), cell(by.get("c")), cell(by.get("d")),
                r.success() ? r.selectedMdcId() : "실패"));
    }

    private static String cell(NodeScore s) {
        if (!s.eligible()) return "제외";
        Double link = s.details().get("링크품질");
        return String.format("%.4f%s", s.totalScore(), link == null ? "" : " (링크 " + String.format("%.3f", link) + ")");
    }

    private static MdcLink link(String a, String b, double latency, MdcLink.LinkStatus st) {
        MdcLink l = new MdcLink(a, b, latency);
        l.setStatus(st);
        return l;
    }

    private static PlacementContext ctx(boolean affinity, List<NodeResource> nodes) {
        SchedulingPolicy p = new SchedulingPolicy("p", "d");
        p.setAffinityEnabled(affinity);
        return PlacementContext.of(nodes, p, ConstraintEvaluator.withDefaults()).withLinkMatrix(LINKS);
    }

    private static Workload workload(String dataMdcId) {
        Workload w = new Workload("w", 1, 512);
        w.setDataMdcId(dataMdcId);
        return w;
    }

    private static List<NodeResource> nodes() {
        return new java.util.ArrayList<>(List.of(node("a"), node("b"), node("c"), node("d")));
    }

    private static NodeResource node(String mdcId) {
        return new NodeResource(mdcId, "n-" + mdcId, 8, 16384, 0, 10, 10, 0, 100, 500, 40, 0, 0);
    }

    private static Map<String, NodeScore> byMdc(SchedulingResult r) {
        return r.candidateScores().stream().collect(Collectors.toMap(NodeScore::mdcId, Function.identity()));
    }
}

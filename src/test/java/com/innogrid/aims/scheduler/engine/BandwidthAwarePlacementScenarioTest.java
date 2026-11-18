package com.innogrid.aims.scheduler.engine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대역폭 부족분 시나리오 — 요구 대역폭이 배치를 어디로 보내는가, 요구가 없으면 예전과 같은가.
 *
 * <pre>
 * 클러스터  MDC a{n-a} · b{n-b} · c{n-c}   자원·부하 동일(트래픽 0)
 * 링크      a-b 20ms UP 10000Mbps(넓음) · a-c 20ms UP 500Mbps(좁음)
 * 정책      affinityEnabled=true, 가중치 기본
 *
 * 지연·손실·상태가 같고 <b>대역폭만 다른</b> 두 경로를 둔다. 그래서 순위가 갈리면 그것은 대역폭 때문이다.
 *
 * ① 요구 없음      → b와 c가 동점(링크 0.714) — 이 항이 생기기 전과 같다
 * ② 요구 500       → 둘 다 충족이라 여전히 동점
 * ③ 요구 1000      → c만 50% 모자라 0.526으로 내려가고 b(0.714)가 이긴다
 * ④ 요구 20000     → 둘 다 모자라지만 b는 50%(0.526)·c는 97.5%(0.421)라 b가 이긴다
 * ⑤ 게이트 off     → 요구 20000이어도 셋이 동점이고 링크 키가 없다
 * </pre>
 */
class BandwidthAwarePlacementScenarioTest {

    private static final double EPS = 1e-3;

    private final WeightedScoreScheduler weighted = new WeightedScoreScheduler();

    private static final LinkMatrix LINKS = LinkMatrix.of(List.of(
            link("a", "b", 20, 10000.0),
            link("a", "c", 20, 500.0)));

    @DisplayName("시나리오 지연이 같고 대역폭만 다른 두 경로에서 요구 대역폭이 순위를 가른다")
    @Test
    void scenario_bandwidth_shortfall_ranks_candidates() {
        StringBuilder table = new StringBuilder("\n| # | 요구(Mbps) | a | b(10000) | c(500) | 선택 |\n|---|---|---|---|---|---|\n");

        // ① 요구 없음 — 이 항이 생기기 전과 같은 값
        Map<String, NodeScore> s1 = run(null, true);
        row(table, "①", "없음", s1, "b·c 동점");
        assertEquals(0.714, s1.get("b").details().get("링크품질"), EPS);
        assertEquals(s1.get("b").details().get("링크품질"), s1.get("c").details().get("링크품질"),
                "요구가 없으면 대역폭 차이는 점수에 나타나지 않는다");
        assertEquals(s1.get("b").totalScore(), s1.get("c").totalScore());

        // ② 요구 500 — 둘 다 충족
        Map<String, NodeScore> s2 = run(500.0, true);
        row(table, "②", "500", s2, "b·c 동점");
        assertEquals(0.714, s2.get("c").details().get("링크품질"), EPS, "딱 채우면 부족분 0");
        assertEquals(s2.get("b").totalScore(), s2.get("c").totalScore());

        // ③ 요구 1000 — c만 부족
        Map<String, NodeScore> s3 = run(1000.0, true);
        row(table, "③", "1000", s3, "b");
        assertEquals(0.714, s3.get("b").details().get("링크품질"), EPS, "넓은 경로는 그대로");
        assertEquals(0.526, s3.get("c").details().get("링크품질"), EPS, "좁은 경로는 50% 모자라 깎인다");
        assertTrue(s3.get("b").totalScore() > s3.get("c").totalScore(), "대역폭만으로 순위가 갈린다");

        // ④ 요구 20000 — 둘 다 부족하지만 정도가 다르다
        Map<String, NodeScore> s4 = run(20000.0, true);
        row(table, "④", "20000", s4, "b");
        assertEquals(0.526, s4.get("b").details().get("링크품질"), EPS, "b 도 50% 모자라다");
        assertEquals(0.421, s4.get("c").details().get("링크품질"), EPS, "c 는 97.5% 모자라다");
        assertTrue(s4.get("b").totalScore() > s4.get("c").totalScore());

        // ⑤ 게이트 off — 요구가 있어도 링크 키가 없고 셋이 동점
        Map<String, NodeScore> s5 = run(20000.0, false);
        row(table, "⑤", "20000 (게이트 off)", s5, "동점");
        for (String mdc : List.of("a", "b", "c")) {
            assertNull(s5.get(mdc).details().get("링크품질"), mdc + " — 게이트가 꺼지면 링크 키가 없다");
        }
        assertEquals(s5.get("b").totalScore(), s5.get("c").totalScore());
        assertEquals(s5.get("a").totalScore(), s5.get("c").totalScore(), "게이트 off 는 세 후보가 전부 동점");

        System.out.println(table);
    }

    @DisplayName("같은 MDC 는 요구 대역폭이 아무리 커도 링크를 타지 않아 1_0 이다")
    @Test
    void the_same_mdc_never_pays_the_shortfall() {
        Map<String, NodeScore> s = run(1000000.0, true);

        assertEquals(1.0, s.get("a").details().get("링크품질"), EPS);
        assertEquals(1.0, s.get("a").details().get("링크등록"), EPS);
        assertTrue(s.get("a").totalScore() > s.get("b").totalScore());
    }

    @DisplayName("게이트가 켜지면 요구 대역폭이 있든 없든 모든 후보가 같은 키 집합을 갖는다")
    @Test
    void the_key_set_does_not_depend_on_the_requirement() {
        for (Double required : new Double[] {null, 1000.0}) {
            Map<String, NodeScore> s = run(required, true);
            for (String mdc : List.of("a", "b", "c")) {
                assertNotNull(s.get(mdc).details().get("링크품질"), mdc + " 요구=" + required);
                assertNotNull(s.get(mdc).details().get("링크등록"), mdc + " 요구=" + required);
            }
        }
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private Map<String, NodeScore> run(Double requiredBandwidthMbps, boolean affinity) {
        Workload w = new Workload("w", 1, 512);
        w.setDataMdcId("a");
        w.setRequiredBandwidthMbps(requiredBandwidthMbps);
        SchedulingResult r = weighted.schedule(w, ctx(affinity));
        return r.candidateScores().stream().collect(Collectors.toMap(NodeScore::mdcId, Function.identity()));
    }

    private static void row(StringBuilder t, String no, String required, Map<String, NodeScore> by, String winner) {
        t.append(String.format("| %s | %s | %s | %s | %s | %s |%n", no, required,
                cell(by.get("a")), cell(by.get("b")), cell(by.get("c")), winner));
    }

    private static String cell(NodeScore s) {
        Double link = s.details().get("링크품질");
        return String.format("%.4f%s", s.totalScore(), link == null ? "" : " (링크 " + String.format("%.3f", link) + ")");
    }

    private static MdcLink link(String a, String b, double latency, Double bandwidthMbps) {
        MdcLink l = new MdcLink(a, b, latency);
        l.setBandwidthMbps(bandwidthMbps);
        return l;
    }

    private static PlacementContext ctx(boolean affinity) {
        SchedulingPolicy p = new SchedulingPolicy("p", "d");
        p.setAffinityEnabled(affinity);
        return PlacementContext.of(nodes(), p, ConstraintEvaluator.withDefaults()).withLinkMatrix(LINKS);
    }

    private static List<NodeResource> nodes() {
        return new java.util.ArrayList<>(List.of(node("a"), node("b"), node("c")));
    }

    private static NodeResource node(String mdcId) {
        return new NodeResource(mdcId, "n-" + mdcId, 8, 16384, 0, 10, 10, 0, 100, 500, 40, 0, 0);
    }
}

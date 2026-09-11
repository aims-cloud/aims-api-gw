package com.innogrid.aims.scheduler.engine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
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
 * 원장에 없는 원격 MDC 의 링크 점수 — 현행 동작을 <b>결함째</b> 고정한다.
 *
 * <p>데이터는 a 에 있고 후보는 a(같은 MDC) · b(a-b 20ms UP 등록) · z(원장에 없음) 셋이다. 지금은 같은 MDC 와
 * 미등록 쌍을 똑같이 1.0 으로 보기 때문에 <b>측정된 양호 링크(b, 0.714)가 측정되지 않은 경로(z)보다 낮게</b>
 * 평가된다. 링크 원장을 채우지 않은 MDC 가 채운 MDC 보다 유리해지는 셈이라, 이 표는 다음 커밋에서 뒤집힌다.</p>
 */
class UnknownMdcLinkScoreTest {

    private static final double EPS = 1e-3;
    private final WeightedScoreScheduler scheduler = new WeightedScoreScheduler();

    private static final LinkMatrix LINKS = LinkMatrix.of(List.of(link("a", "b", 20)));

    @DisplayName("현행 미등록 원격 z 가 같은 MDC a 와 동점이고 등록된 20ms 링크 b 보다 앞선다")
    @Test
    void unregistered_remote_currently_ties_same_mdc_and_beats_registered_link() {
        SchedulingResult r = scheduler.schedule(workload("a"), ctx(true));
        Map<String, NodeScore> by = byMdc(r);

        assertEquals(1.0, by.get("a").details().get("링크품질"), EPS, "같은 MDC");
        assertEquals(0.714, by.get("b").details().get("링크품질"), EPS, "등록 20ms UP");
        assertEquals(1.0, by.get("z").details().get("링크품질"), EPS, "미등록 — 같은 MDC 와 구별되지 않는다");
        assertEquals(by.get("a").totalScore(), by.get("z").totalScore(), EPS, "동점");
        assertTrue(by.get("z").totalScore() > by.get("b").totalScore(), "측정된 링크가 측정되지 않은 경로에 진다");
        assertTrue(by.values().stream().allMatch(NodeScore::eligible), "미등록은 제외 대상이 아니다");
    }

    @DisplayName("게이트가 꺼지면 세 후보가 동점이고 링크 키가 없다")
    @Test
    void gate_off_keeps_all_equal_without_link_keys() {
        SchedulingResult r = scheduler.schedule(workload("a"), ctx(false));
        Map<String, NodeScore> by = byMdc(r);

        double first = by.get("a").totalScore();
        assertTrue(by.values().stream().allMatch(s -> Math.abs(s.totalScore() - first) < EPS));
        assertTrue(by.values().stream().noneMatch(s -> s.details().containsKey("링크품질")));
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private static MdcLink link(String a, String b, double latency) {
        return new MdcLink(a, b, latency);
    }

    private static PlacementContext ctx(boolean affinity) {
        SchedulingPolicy p = new SchedulingPolicy("p", "d");
        p.setAffinityEnabled(affinity);
        return PlacementContext.of(List.of(node("a"), node("b"), node("z")), p).withLinkMatrix(LINKS);
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

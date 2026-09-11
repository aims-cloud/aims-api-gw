package com.innogrid.aims.scheduler.engine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.NetworkModel;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
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
 * 원장에 없는 원격 MDC 의 링크 점수.
 *
 * <p>데이터는 a 에 있고 후보는 a(같은 MDC) · b(a-b 20ms UP 등록) · z(원장에 없음) 셋이다. 예전에는 같은 MDC 와
 * 미등록 쌍을 똑같이 1.0 으로 보아 <b>측정된 양호 링크(b, 0.714)가 측정되지 않은 경로(z)보다 낮게</b> 평가됐다
 * — 링크 원장을 채운 MDC 가 채우지 않은 MDC 보다 불리했다. 지금은 z 가 설정값(기본 0.5)을 받아 b 아래로 간다.
 * 순위 a > b > z 가 이 수정의 증거다. 설정을 1.0 으로 돌리면 예전 순위가 그대로 나온다.</p>
 */
class UnknownMdcLinkScoreTest {

    private static final double EPS = 1e-3;
    private final WeightedScoreScheduler scheduler = new WeightedScoreScheduler();

    private static final LinkMatrix LINKS = LinkMatrix.of(List.of(link("a", "b", 20)));

    @DisplayName("미등록 원격 z 는 0_5 를 받아 같은 MDC a 와 등록된 20ms 링크 b 아래에 선다")
    @Test
    void unregistered_remote_ranks_below_registered_link() {
        SchedulingResult r = scheduler.schedule(workload("a"), ctx(true));
        Map<String, NodeScore> by = byMdc(r);

        assertEquals(1.0, by.get("a").details().get("링크품질"), EPS, "같은 MDC");
        assertEquals(0.714, by.get("b").details().get("링크품질"), EPS, "등록 20ms UP");
        assertEquals(0.5, by.get("z").details().get("링크품질"), EPS, "미등록 원격 — 기본 0.5");
        assertTrue(by.get("a").totalScore() > by.get("b").totalScore(), "같은 MDC > 등록 링크");
        assertTrue(by.get("b").totalScore() > by.get("z").totalScore(), "등록 링크 > 미등록");
        assertEquals("a", r.selectedMdcId());
        assertTrue(by.values().stream().allMatch(NodeScore::eligible), "미등록은 제외 대상이 아니다");
        // 총점 차이는 링크 0.5 × 혼합 0.5 × 네트워크 가중치 0.15 = 0.0375 — 순위는 바꾸되 압도하지는 않는다
        assertEquals(0.0375, by.get("a").totalScore() - by.get("z").totalScore(), EPS);
    }

    @DisplayName("설정을 1_0 으로 두면 예전처럼 미등록이 같은 MDC 와 동점이다")
    @Test
    void unknown_score_1_0_restores_previous_ranking() {
        SchedulerProperties props = new SchedulerProperties();
        props.getNetwork().setUnknownLinkScore(1.0);
        WeightedScoreScheduler legacy = new WeightedScoreScheduler(
                new PowerModel(props), new ThermalModel(props), new NetworkModel(props));

        Map<String, NodeScore> by = byMdc(legacy.schedule(workload("a"), ctx(true)));

        assertEquals(1.0, by.get("z").details().get("링크품질"), EPS);
        assertEquals(by.get("a").totalScore(), by.get("z").totalScore(), EPS, "예전 동작 — 동점");
        assertTrue(by.get("z").totalScore() > by.get("b").totalScore());
    }

    @DisplayName("MDC 라벨이 없는 노드도 원격 미등록과 같은 점수다")
    @Test
    void unlabeled_node_scores_like_unregistered() {
        SchedulingPolicy p = new SchedulingPolicy("p", "d");
        p.setAffinityEnabled(true);
        PlacementContext ctx = PlacementContext.of(List.of(node("a"), node(null)), p).withLinkMatrix(LINKS);

        SchedulingResult r = scheduler.schedule(workload("a"), ctx);
        NodeScore unlabeled = r.candidateScores().stream().filter(s -> s.mdcId() == null).findFirst().orElseThrow();

        assertEquals(0.5, unlabeled.details().get("링크품질"), EPS);
        assertTrue(unlabeled.eligible(), "C5 도 라벨 없는 노드를 통과시킨다");
        assertEquals("a", r.selectedMdcId());
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

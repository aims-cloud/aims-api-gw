package com.innogrid.aims.scheduler.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * power 엔진의 {@code 통합여유} — 가동 중 후보 최고 총점 − 빈 후보 최고 총점. 점수·순위·선택은 그대로, 보이게만 한다.
 */
class ConsolidationMarginTest {

    private static final double EPS = 1e-9;
    private final PowerAwareScheduler scheduler = new PowerAwareScheduler();

    @Test
    void 가동_노드와_빈_노드가_함께_있으면_모든_후보에_같은_여유_값이_붙는다() {
        NodeResource active = node("active", 50, 250, 55);   // CPU 50% > 5% 가동
        NodeResource idle = node("idle", 1, 160, 40);         // 빈 노드, 차갑다
        SchedulingResult r = scheduler.schedule(new Workload("w", 2, 1024), List.of(active, idle), policy());

        NodeScore a = byId(r, "active"), i = byId(r, "idle");
        assertEquals(1.0, a.details().get("가동중")); assertEquals(0.0, i.details().get("가동중"));
        double expected = Math.round((a.totalScore() - i.totalScore()) * 10000.0) / 10000.0;
        assertEquals(expected, a.details().get("통합여유"), EPS);
        assertEquals(expected, i.details().get("통합여유"), EPS, "모든 후보에 같은 값");
        // 선택은 종전과 같다 — 여유가 양수면 가동 노드, 음수면 빈 노드
        assertEquals(expected > 0 ? "active" : "idle", r.selectedNodeId());
    }

    @Test
    void 한쪽_집단만_있으면_키를_넣지_않는다() {
        SchedulingResult onlyActive = scheduler.schedule(new Workload("w", 2, 1024),
                List.of(node("a1", 50, 250, 55), node("a2", 40, 240, 50)), policy());
        assertTrue(onlyActive.candidateScores().stream().noneMatch(s -> s.details().containsKey("통합여유")));

        SchedulingResult onlyIdle = scheduler.schedule(new Workload("w", 2, 1024),
                List.of(node("i1", 1, 150, 40)), policy());
        assertTrue(onlyIdle.candidateScores().stream().noneMatch(s -> s.details().containsKey("통합여유")));
    }

    @Test
    void 적격이_아닌_후보는_여유_계산에서_빠진다() {
        // 빈 노드가 자원 부족으로 제외되면 "빈 집단"이 없다 → 키 없음
        NodeResource active = node("active", 50, 250, 55);
        NodeResource idleTiny = new NodeResource("mdc-01", "idle-tiny", 1, 512, 0, 1, 10, 0, 150, 500, 40, 0, 0);
        SchedulingResult r = scheduler.schedule(new Workload("w", 2, 1024), List.of(active, idleTiny), policy());

        assertFalse(byId(r, "idle-tiny").eligible());
        assertTrue(r.candidateScores().stream().noneMatch(s -> s.details().containsKey("통합여유")));
    }

    private static NodeResource node(String id, double cpu, double watts, double temp) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, cpu, 40, 0, watts, 500, temp, 0, 0);
    }

    private static SchedulingPolicy policy() {
        return new SchedulingPolicy("p", "d");
    }

    private static NodeScore byId(SchedulingResult r, String id) {
        return r.candidateScores().stream().filter(s -> s.nodeId().equals(id)).findFirst().orElseThrow();
    }
}

package com.innogrid.aims.scheduler.engine;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 후보 상세의 {@code 디스크검사} — 세 엔진이 같은 규약으로 낸다.
 *
 * <p>총량을 모르는 노드는 C1 디스크 검사를 건너뛰고 적격이 된다(옳은 판정). 이 테스트는 그 판정을 바꾸지 않고
 * <b>건너뛴 사실이 응답에 남는지</b>만 본다: 요구가 있으면 모든 후보에 키가 붙고(총량 알면 1.0, 모르면 0.0),
 * 요구가 없으면 키가 없어 기존 응답의 키 집합이 그대로다.</p>
 */
class DiskCheckDetailTest {

    private static final SchedulingPolicy POLICY = new SchedulingPolicy("t", "t");

    private static NodeResource node(String id, long diskGb) {
        NodeResource n = new NodeResource("mdc-01", id, 8, 16384, 0, 10, 20, 30, 200, 500, 45, 1000, 500);
        return diskGb > 0 ? n.withDiskCapacityGb(diskGb) : n;
    }

    private static Workload workload(int diskGb) {
        Workload w = new Workload("w", 2, 1024);
        w.setRequiredDiskGb(diskGb);
        return w;
    }

    private static Map<String, Double> details(SchedulingResult r, String nodeId) {
        return r.candidateScores().stream().filter(s -> s.nodeId().equals(nodeId)).findFirst().map(NodeScore::details).orElseThrow();
    }

    private static List<SchedulingEngine> engines() {
        return List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(), new TemperatureAwareScheduler());
    }

    @DisplayName("디스크 요구가 있으면 세 엔진 모두 후보마다 디스크검사 키 — 총량 알면 1.0, 모르면 0.0")
    @Test
    void every_engine_marks_disk_check_per_candidate() {
        for (SchedulingEngine engine : engines()) {
            List<NodeResource> nodes = List.of(node("known", 500), node("unknown", 0));
            SchedulingResult r = engine.schedule(workload(100), nodes, POLICY);

            assertEquals(1.0, details(r, "known").get("디스크검사"), engine.getName() + " — 총량을 알면 검사 적용");
            assertEquals(0.0, details(r, "unknown").get("디스크검사"), engine.getName() + " — 총량 모름은 검사 없이 적격");
            assertTrue(r.candidateScores().stream().allMatch(NodeScore::eligible), engine.getName() + " — 판정은 그대로 둘 다 적격");
        }
    }

    @DisplayName("디스크 요구가 없으면 키가 붙지 않는다 — 기존 배치의 상세 키 집합은 그대로")
    @Test
    void no_disk_requirement_means_no_key() {
        for (SchedulingEngine engine : engines()) {
            SchedulingResult r = engine.schedule(workload(0), List.of(node("known", 500), node("unknown", 0)), POLICY);
            assertFalse(details(r, "known").containsKey("디스크검사"), engine.getName());
            assertFalse(details(r, "unknown").containsKey("디스크검사"), engine.getName());
        }
    }

    @DisplayName("부적격 후보도 키를 갖는다 — 값은 검사 대상이었나이고 적격 여부와 무관")
    @Test
    void ineligible_candidate_still_carries_the_key() {
        NodeResource small = node("small", 100);       // 가용 70 < 200 → INSUFFICIENT_DISK
        NodeResource unknown = node("unknown", 0);
        SchedulingResult r = new WeightedScoreScheduler().schedule(workload(200), List.of(small, unknown), POLICY);

        NodeScore s = r.candidateScores().stream().filter(x -> x.nodeId().equals("small")).findFirst().orElseThrow();
        assertFalse(s.eligible());
        assertEquals(1.0, s.details().get("디스크검사"), "검사가 적용돼 부족으로 걸렸다");
        assertEquals("unknown", r.selectedNodeId(), "총량 모름 노드가 검사 없이 받는다");
    }

    private static ch.qos.logback.classic.Logger evaluatorLog() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DiskCheckDetailTest.class);
    }

    @DisplayName("WARN 은 미검사 적격 노드가 있을 때 한 줄 — 노드 id·마운트 설정 키가 본문에 있고, 없으면 0건")
    @Test
    void warn_is_one_line_with_node_and_mount_or_nothing() {
        ch.qos.logback.classic.Logger log = evaluatorLog();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events = new ch.qos.logback.core.read.ListAppender<>();
        events.start(); log.addAppender(events);
        try {
            NodeResource known = node("known", 500), unknown = node("unknown", 0);
            PlacementContext ctx = PlacementContext.of(List.of(known, unknown), POLICY);
            Workload w = workload(100);
            SchedulingResult r = new WeightedScoreScheduler().schedule(w, ctx);

            ConstraintEvaluator.warnUncheckedDisk(log, w, r, ctx, "/data");
            assertEquals(1, events.list.size(), "배치 1회에 한 줄");
            String msg = events.list.get(0).getFormattedMessage();
            assertEquals(ch.qos.logback.classic.Level.WARN, events.list.get(0).getLevel());
            assertTrue(msg.contains("unknown") && !msg.contains("known,"), "미검사 노드만 예시로: " + msg);
            assertTrue(msg.contains("aims.scheduler.disk-mount-point='/data'"), "설정 키와 값이 함께: " + msg);
            assertTrue(msg.contains("1대"), msg);

            events.list.clear();
            ConstraintEvaluator.warnUncheckedDisk(log, workload(0), r, ctx, "/");
            PlacementContext allKnown = PlacementContext.of(List.of(node("a", 500), node("b", 500)), POLICY);
            ConstraintEvaluator.warnUncheckedDisk(log, w, new WeightedScoreScheduler().schedule(w, allKnown), allKnown, "/");
            assertEquals(0, events.list.size(), "요구 없음·전부 계측이면 조용하다");
        } finally {
            log.detachAppender(events);
        }
    }

    @DisplayName("미검사 노드가 넷 이상이면 예시는 셋까지 나열하고 줄임표로 끝난다")
    @Test
    void warn_lists_at_most_three_nodes() {
        ch.qos.logback.classic.Logger log = evaluatorLog();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events = new ch.qos.logback.core.read.ListAppender<>();
        events.start(); log.addAppender(events);
        try {
            List<NodeResource> nodes = List.of(node("u1", 0), node("u2", 0), node("u3", 0), node("u4", 0), node("u5", 0));
            PlacementContext ctx = PlacementContext.of(nodes, POLICY);
            Workload w = workload(100);
            ConstraintEvaluator.warnUncheckedDisk(log, w, new WeightedScoreScheduler().schedule(w, ctx), ctx, "/");

            assertEquals(1, events.list.size());
            String msg = events.list.get(0).getFormattedMessage();
            assertTrue(msg.contains("5대"), msg);
            assertTrue(msg.contains(", …"), "넷 이상은 줄임표: " + msg);
            assertEquals(3, java.util.regex.Pattern.compile("u\\d").matcher(msg).results().count(), "예시 노드는 셋까지: " + msg);
        } finally {
            log.detachAppender(events);
        }
    }

    @DisplayName("검사 없이 적격이 된 노드 목록 — 요구 없음·전부 계측이면 빈 목록, 강등돼 상세가 비어도 예외 없음")
    @Test
    void unchecked_eligible_list_and_warn() {
        NodeResource known = node("known", 500), unknown = node("unknown", 0);
        PlacementContext ctx = PlacementContext.of(List.of(known, unknown), POLICY);
        Workload w = workload(100);
        SchedulingResult r = new WeightedScoreScheduler().schedule(w, ctx);

        assertEquals(List.of("unknown"), ConstraintEvaluator.uncheckedDiskEligible(w, r, ctx));
        assertEquals(List.of(), ConstraintEvaluator.uncheckedDiskEligible(workload(0), r, ctx), "요구 없으면 표기할 것이 없다");
        assertEquals(List.of(), ConstraintEvaluator.uncheckedDiskEligible(w, SchedulingResult.failure("강등"), ctx), "상세가 비면 빈 목록");
        assertDoesNotThrow(() -> ConstraintEvaluator.warnUncheckedDisk(LoggerFactory.getLogger(getClass()), w, r, ctx, "/"));
    }
}

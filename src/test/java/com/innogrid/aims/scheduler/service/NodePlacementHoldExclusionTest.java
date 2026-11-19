package com.innogrid.aims.scheduler.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.Exclusion;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.NodePlacementHold;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.NodePlacementHolds;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.NodePlacementHoldRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드 배치 중지 — 제약 평가의 맨 앞에서 걸리는지, 같은 입력에서 원장 행만 뺀 대조와 결과가 갈리는지, 조립 · 사본 · 실행 입력 ·
 * 재생이 중지 목록을 잇는지.
 *
 * <p>제외를 확인하는 테스트마다 원장 행만 없는 같은 입력을 대조로 둔다. 중지 노드가 다른 까닭(자원 부족 등)으로도 빠지는
 * 입력이면 「선택되지 않음」 확인이 아무것도 보지 않고 통과하기 때문이다. 스프링 없이 돈다.</p>
 */
class NodePlacementHoldExclusionTest {

    private final ConstraintEvaluator evaluator = ConstraintEvaluator.withDefaults();

    // ── 평가 순서 · 사유 ───────────────────────────────────────

    @DisplayName("중지 노드는 자원이 모자라도 자원 부족이 아니라 배치 중지 사유로 빠진다")
    @Test
    void held_node_is_excluded_before_resource_check() {
        NodeResource small = node("n-small", 2, 0);
        Workload big = new Workload("w", 8, 1024);

        Exclusion held = evaluator.exclusion(big, small, ctx(List.of(small), Map.of("n-small", "디스크 교체")));
        Exclusion control = evaluator.exclusion(big, small, ctx(List.of(small), Map.of()));

        assertEquals(ExclusionReason.NODE_PLACEMENT_HOLD, held.reason());
        assertEquals("운영자 배치 중지 (사유: 디스크 교체)", held.message());
        assertEquals(ExclusionReason.INSUFFICIENT_RESOURCE, control.reason(), "대조: 원장 행이 없으면 자원 부족으로 빠진다");
    }

    @DisplayName("사유가 없으면 문장은 「운영자 배치 중지」이고 문장만 주는 진입점도 같은 문장이다")
    @Test
    void reason_less_hold_message_is_same_on_both_entry_points() {
        NodeResource idle = node("n-idle", 64, 10);
        PlacementContext ctx = ctx(List.of(idle), Map.of("n-idle", ""));
        Workload w = new Workload("w", 1, 64);

        assertEquals("운영자 배치 중지", evaluator.exclusion(w, idle, ctx).message());
        assertEquals("운영자 배치 중지", evaluator.exclusionReason(w, idle, ctx));
        assertNull(evaluator.exclusion(w, idle, ctx(List.of(idle), Map.of())), "대조: 원장 행이 없으면 적격");
    }

    @DisplayName("노드 id 는 대소문자를 구분해 정확히 맞춘다")
    @Test
    void hold_match_is_exact_and_case_sensitive() {
        NodePlacementHolds holds = new NodePlacementHolds(Map.of("Node-A", "점검"));

        assertTrue(holds.isHeld("Node-A"));
        assertFalse(holds.isHeld("node-a"));
        assertFalse(holds.isHeld("Node-A2"));
        assertFalse(holds.isHeld(null));
        assertEquals("점검", holds.reasonOf("Node-A"));
        assertEquals("", holds.reasonOf("node-a"));
    }

    @DisplayName("원장 행으로 만든 스냅샷은 노드 id 순이고 사유가 없으면 빈 문자열이다")
    @Test
    void snapshot_from_rows_is_sorted() {
        NodePlacementHold b = new NodePlacementHold("node-b");
        NodePlacementHold a = new NodePlacementHold("node-a");
        a.setReason("점검");

        NodePlacementHolds holds = NodePlacementHolds.of(List.of(b, a));

        assertEquals(List.of("node-a", "node-b"), List.copyOf(holds.reasonByNode().keySet()));
        assertEquals("", holds.reasonOf("node-b"));
        assertSame(NodePlacementHolds.EMPTY, NodePlacementHolds.of(List.of()));
    }

    // ── 엔진 결정 ──────────────────────────────────────────────

    @DisplayName("엔진은 중지 노드를 빼고 다른 노드를 고르며 그 후보에 배치 중지 코드를 남긴다")
    @Test
    void engine_picks_other_node_and_reports_hold_code() {
        List<NodeResource> nodes = List.of(node("n-big", 64, 5), node("n-small", 16, 40));
        Workload w = new Workload("w", 2, 512);

        SchedulingResult held = new WeightedScoreScheduler().schedule(w, ctx(nodes, Map.of("n-big", "점검")));
        SchedulingResult control = new WeightedScoreScheduler().schedule(w, ctx(copy(nodes), Map.of()));

        assertTrue(held.success(), held.reason());
        assertEquals("n-small", held.selectedNodeId());
        SchedulingResult.NodeScore big = held.candidateScores().stream()
                .filter(s -> s.nodeId().equals("n-big")).findFirst().orElseThrow();
        assertFalse(big.eligible());
        assertEquals(ExclusionReason.NODE_PLACEMENT_HOLD, big.exclusionCode());
        assertEquals("운영자 배치 중지 (사유: 점검)", big.exclusionReason());
        assertEquals("n-big", control.selectedNodeId(), "대조: 원장 행이 없으면 중지했던 노드가 고른 노드다");
    }

    @DisplayName("모든 노드를 중지하면 배치가 실패하고 후보마다 배치 중지 코드다")
    @Test
    void all_nodes_held_fails() {
        List<NodeResource> nodes = List.of(node("n-1", 64, 5), node("n-2", 64, 5));

        SchedulingResult result = new WeightedScoreScheduler().schedule(new Workload("w", 1, 64),
                ctx(nodes, Map.of("n-1", "", "n-2", "")));

        assertFalse(result.success());
        assertEquals(2, result.candidateScores().size());
        assertTrue(result.candidateScores().stream()
                .allMatch(s -> s.exclusionCode() == ExclusionReason.NODE_PLACEMENT_HOLD));
    }

    // ── 조립 · 사본 ────────────────────────────────────────────

    @DisplayName("조립기는 원장을 실행당 한 번 읽어 싣고, 원장을 붙이지 않으면 빈 값이다")
    @Test
    void assembler_reads_ledger_once() {
        NodeResourceCollector collector = mock(NodeResourceCollector.class);
        when(collector.collect())
                .thenReturn(new NodeResourceCollector.Snapshot(List.of(node("n-1", 8, 10)), List.of()));
        NodePlacementHoldRepository repository = mock(NodePlacementHoldRepository.class);
        NodePlacementHold row = new NodePlacementHold("n-1");
        row.setReason("점검");
        when(repository.findAll()).thenReturn(List.of(row));

        PlacementContext withLedger = SchedulingServiceBuilder.aSchedulingService()
                .collector(collector).holds(repository).build().createContext(new SchedulingPolicy("p", "d"));
        PlacementContext without = SchedulingServiceBuilder.aSchedulingService()
                .collector(collector).build().createContext(new SchedulingPolicy("p", "d"));

        verify(repository, times(1)).findAll();
        assertTrue(withLedger.placementHolds().isHeld("n-1"));
        assertSame(NodePlacementHolds.EMPTY, without.placementHolds());
    }

    @DisplayName("사본(sandbox)은 중지 목록을 이어받아 같은 노드를 뺀다")
    @Test
    void sandbox_carries_holds() {
        List<NodeResource> nodes = List.of(node("n-1", 64, 5), node("n-2", 64, 5));
        PlacementContext source = ctx(nodes, Map.of("n-1", "점검"));

        PlacementContext sandbox = source.sandbox(copy(nodes));

        assertTrue(sandbox.placementHolds().isHeld("n-1"));
        assertEquals(ExclusionReason.NODE_PLACEMENT_HOLD,
                sandbox.exclusion(new Workload("w", 1, 64), sandbox.nodes().get(0)).reason());
    }

    // ── 실행 입력 · 재생 ───────────────────────────────────────

    @DisplayName("실행 입력은 중지 목록을 노드 id 순으로 담고, 재생은 그 목록으로 같은 결정을 낸다")
    @Test
    void capture_records_holds_and_replay_applies_them() {
        SchedulerProperties props = new SchedulerProperties();
        List<NodeResource> nodes = List.of(node("n-big", 64, 5), node("n-small", 16, 40));
        PlacementContext ctx = ctx(nodes, Map.of("n-big", "점검", "a-other", ""));
        Workload w = new Workload("w", 2, 512);

        RunInputCapture.RunInputs in = new RunInputCapture(new ThermalModel(props), props)
                .inputsOf("weighted", Instant.parse("2026-11-19T00:00:00Z"), ctx, List.of(w));

        assertEquals(RunInputCapture.FORMAT_VERSION, in.formatVersion());
        assertEquals(List.of(new RunInputCapture.HoldRow("a-other", ""), new RunInputCapture.HoldRow("n-big", "점검")),
                in.placementHolds());
        RunInputReplay.Rebuilt rebuilt = RunInputReplay.rebuild(in, "weighted");
        assertEquals("n-small", rebuilt.engine().schedule(w, rebuilt.context()).selectedNodeId());

        RunInputCapture.RunInputs noHolds = new RunInputCapture.RunInputs(in.formatVersion(), in.engineId(),
                in.capturedAt(), in.powerBudgetWatts(), in.nodes(), in.excludedNodes(), in.workloads(), in.policy(),
                in.mdcBudgets(), in.links(),
                null, in.cooldowns(), in.knobs());
        RunInputReplay.Rebuilt control = RunInputReplay.rebuild(noHolds, "weighted");
        assertEquals("n-big", control.engine().schedule(w, control.context()).selectedNodeId(),
                "대조: 중지 목록이 없으면 재생은 중지했던 노드를 고른다");
    }

    @DisplayName("판 2 입력은 문자열로 바꿨다가 읽어 다시 써도 같은 문자열이다")
    @Test
    void format_2_payload_round_trips() {
        SchedulerProperties props = new SchedulerProperties();
        PlacementContext ctx = ctx(List.of(node("n-1", 8, 10)), Map.of("n-1", "점검"));
        String payload = new RunInputCapture(new ThermalModel(props), props)
                .payloadOf("weighted", Instant.parse("2026-11-19T00:00:00Z"), ctx, List.of(new Workload("w", 1, 64)));

        RunInputCapture.RunInputs read = RunInputCapture.read(payload);

        assertEquals(2, read.formatVersion());
        assertEquals(List.of(new RunInputCapture.HoldRow("n-1", "점검")), read.placementHolds());
        assertEquals(payload, RunInputCapture.MAPPER.writeValueAsString(read));
        assertTrue(RunInputCapture.readable(2));
        assertFalse(RunInputCapture.readable(3));
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private PlacementContext ctx(List<NodeResource> nodes, Map<String, String> holds) {
        return PlacementContext.of(nodes, new SchedulingPolicy("test", "test"), evaluator)
                .withPlacementHolds(new NodePlacementHolds(holds));
    }

    /** CPU 코어 수와 사용률만 다른 노드(메모리 64GB · GPU 0). */
    private static NodeResource node(String id, int cpu, double cpuUsage) {
        return new NodeResource("mdc-a", id, cpu, 65536, 0, cpuUsage, 20, 15, 400, 1000, 40, 0, 0);
    }

    private static List<NodeResource> copy(List<NodeResource> nodes) {
        return nodes.stream().map(n -> node(n.getNodeId(), n.getTotalCpu(), n.getCpuUsagePercent())).toList();
    }
}

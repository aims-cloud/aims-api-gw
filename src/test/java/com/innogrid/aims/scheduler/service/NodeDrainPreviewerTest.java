package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.NodeDrainPreviewResponse;
import com.innogrid.aims.scheduler.dto.NodeDrainPreviewResponse.Decision;
import com.innogrid.aims.scheduler.dto.NodeReservationsResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.MdcPowerBudget;
import com.innogrid.aims.scheduler.entity.NodePlacementHold;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.MdcLinkRepository;
import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;
import com.innogrid.aims.scheduler.repository.NodePlacementHoldRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static com.innogrid.aims.scheduler.entity.Workload.WorkloadPriority.CRITICAL;
import static com.innogrid.aims.scheduler.entity.Workload.WorkloadPriority.HIGH;
import static com.innogrid.aims.scheduler.entity.Workload.WorkloadPriority.LOW;
import static com.innogrid.aims.scheduler.entity.Workload.WorkloadPriority.NORMAL;
import static com.innogrid.aims.scheduler.entity.Workload.WorkloadStatus.RUNNING;
import static com.innogrid.aims.scheduler.entity.Workload.WorkloadStatus.SCHEDULED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드 비우기 모의 배치 — 누적, 비울 노드 제외, 놓는 순서, 종류별 정책, 다른 중지 노드, 냉각 대기를 걸지 않음, 다른 노드 0, 상한,
 * 예약 내역과 같은 판정, 저장 0, 404 판정, 링크 게이트, 조립기 동치.
 *
 * <p>누적 · 제외 · 정책 · 중지 · 링크 · 냉각 · 조립기 테스트는 원인 하나만 바꾼 입력을 대조로 둔다. 스프링 없이 돈다 — 저장소는 목 객체, 워크로드는
 * 엔티티에 id · 상태 · 등록 시각만 채운다.</p>
 */
class NodeDrainPreviewerTest {

    private static final String X = "drain:9100";

    private final NodeResourceCollector collector = mock(NodeResourceCollector.class);
    private final WorkloadRepository workloads = mock(WorkloadRepository.class);
    private final SchedulingPolicyRepository policies = mock(SchedulingPolicyRepository.class);
    private final NodePlacementHoldRepository holds = mock(NodePlacementHoldRepository.class);
    private final MdcLinkRepository links = mock(MdcLinkRepository.class);
    private final MdcPowerBudgetRepository budgets = mock(MdcPowerBudgetRepository.class);
    private final SchedulerProperties properties = new SchedulerProperties();
    private ConstraintEvaluator evaluator = ConstraintEvaluator.withDefaults();

    // ── 누적 · 비울 노드 · 순서 ─────────────────────────────────

    @DisplayName("성공한 건은 옮길 노드에 예약으로 쌓여 다음 건이 보고, 앞 건이 없으면 같은 건이 들어간다")
    @Test
    void placements_accumulate_across_targets() {
        nodes(node(X, 16, 10), node("other:9100", 4, 0));
        Workload first = placed(1, "a", X, SCHEDULED, 3, HIGH, 1);
        Workload second = placed(2, "b", X, SCHEDULED, 3, NORMAL, 2);
        held(first, second);

        NodeDrainPreviewResponse r = service().drainPreview(X, null);

        assertEquals(2, r.workloadCount());
        assertEquals(List.of(1L, 2L), ids(r));
        assertTrue(r.decisions().get(0).success());
        assertEquals("other:9100", r.decisions().get(0).selectedNodeId());
        assertFalse(r.decisions().get(1).success(), "앞 건이 잡은 3코어 때문에 남은 1코어로는 못 들어간다");
        assertEquals("{\"INSUFFICIENT_RESOURCE\":1}", r.decisions().get(1).exclusionCodes());
        assertEquals(1, r.placeableCount());
        assertFalse(r.allPlaceable());

        held(placed(2, "b", X, SCHEDULED, 3, NORMAL, 2));
        NodeDrainPreviewResponse control = service().drainPreview(X, null);
        assertTrue(control.decisions().get(0).success(), "대조: 앞 건이 없으면 같은 워크로드가 들어간다");
        assertTrue(control.allPlaceable());
    }

    @DisplayName("비울 노드는 가장 여유로워도 옮길 곳에 나오지 않고, 그 노드까지 보는 모의 배치는 그 노드를 고른다")
    @Test
    void drained_node_is_never_a_destination() {
        nodes(node(X, 64, 5), node("small:9100", 16, 40));
        held(placed(1, "a", X, SCHEDULED, 2, NORMAL, 1));

        NodeDrainPreviewResponse r = service().drainPreview(" " + X + " ", null);

        assertEquals(X, r.nodeId(), "앞뒤 공백은 뺀다");
        assertTrue(r.collected());
        assertEquals(1, r.otherNodeCount());
        assertEquals("small:9100", r.decisions().get(0).selectedNodeId());
        SchedulingResult control = service().dryRun(new Workload("d", 2, 512), null);
        assertEquals(X, control.selectedNodeId(), "대조: 비울 노드를 빼지 않으면 그 노드를 고른다");
    }

    @DisplayName("배치 대기를 먼저, 실행 중을 다음에 놓고 묶음 안은 우선순위 → 등록 순이다")
    @Test
    void scheduled_first_then_running_by_priority_and_age() {
        nodes(node(X, 64, 5), node("big:9100", 64, 5));
        held(placed(5, "run-critical", X, RUNNING, 1, CRITICAL, 1),
                placed(3, "low-old", X, SCHEDULED, 1, LOW, 2),
                placed(4, "high-new", X, SCHEDULED, 1, HIGH, 9),
                placed(6, "low-new", X, SCHEDULED, 1, LOW, 5));

        assertEquals(List.of(4L, 3L, 6L, 5L), ids(service().drainPreview(X, null)));
    }

    @DisplayName("자리가 하나뿐이면 우선순위가 높아도 실행 중보다 배치 대기가 먼저 자리를 잡는다")
    @Test
    void scheduled_takes_the_only_slot_before_running() {
        nodes(node(X, 16, 10), node("other:9100", 4, 0));
        held(placed(1, "run", X, RUNNING, 3, CRITICAL, 1), placed(2, "wait", X, SCHEDULED, 3, LOW, 2));

        NodeDrainPreviewResponse r = service().drainPreview(X, null);

        assertEquals(List.of(2L, 1L), ids(r));
        assertTrue(r.decisions().get(0).success());
        assertFalse(r.decisions().get(1).success());
        assertEquals(RUNNING, r.decisions().get(1).status());
    }

    // ── 정책 · 원장 ────────────────────────────────────────────

    @DisplayName("워크로드 종류마다 그 종류의 정책으로 놓고, 종류 정책이 없으면 같은 워크로드가 공통 정책으로 들어간다")
    @Test
    void each_resource_type_uses_its_policy() {
        nodes(node(X, 16, 10), node("other:9100", 64, 30));
        Workload gpu = placed(1, "g", X, SCHEDULED, 1, NORMAL, 1);
        gpu.setResourceType(Workload.ResourceType.GPU);
        Workload vm = placed(2, "v", X, SCHEDULED, 1, NORMAL, 2);
        held(gpu, vm);
        SchedulingPolicy strict = policy("gpu-strict");
        strict.setMaxCpuUsagePercent(20);
        when(policies.findByActiveTrueAndResourceTypeOrderByNameAsc(Workload.ResourceType.GPU))
                .thenReturn(List.of(strict));
        when(policies.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()).thenReturn(List.of(policy("common")));

        NodeDrainPreviewResponse r = service().drainPreview(X, null);

        Decision g = r.decisions().get(0);
        assertEquals(Workload.ResourceType.GPU, g.resourceType());
        assertEquals("gpu-strict", g.policyName());
        assertFalse(g.success());
        assertEquals("{\"CPU_USAGE_EXCEEDED\":1}", g.exclusionCodes());
        assertEquals("common", r.decisions().get(1).policyName());
        assertTrue(r.decisions().get(1).success());

        when(policies.findByActiveTrueAndResourceTypeOrderByNameAsc(Workload.ResourceType.GPU))
                .thenReturn(List.of());
        NodeDrainPreviewResponse control = service().drainPreview(X, null);
        assertEquals("common", control.decisions().get(0).policyName());
        assertTrue(control.decisions().get(0).success(), "대조: GPU 정책이 없으면 공통 정책으로 들어간다");
    }

    @DisplayName("다른 중지 노드는 옮길 곳에서 배치 중지 코드로 빠지고, 비울 노드가 중지면 사유를 싣는다")
    @Test
    void other_held_node_is_excluded_and_own_hold_is_reported() {
        nodes(node(X, 16, 10), node("held:9100", 64, 5));
        held(placed(1, "a", X, SCHEDULED, 1, NORMAL, 1));
        NodePlacementHold own = new NodePlacementHold(X);
        own.setReason("디스크 교체");
        when(holds.findAll()).thenReturn(List.of(own, new NodePlacementHold("held:9100")));

        NodeDrainPreviewResponse r = service().drainPreview(X, null);

        assertTrue(r.held());
        assertEquals("디스크 교체", r.holdReason());
        assertFalse(r.decisions().get(0).success());
        assertEquals("{\"NODE_PLACEMENT_HOLD\":1}", r.decisions().get(0).exclusionCodes());

        when(holds.findAll()).thenReturn(List.of(new NodePlacementHold(X)));
        NodeDrainPreviewResponse control = service().drainPreview(X, null);
        assertTrue(control.held());
        assertNull(control.holdReason(), "사유가 없으면 null");
        assertEquals("held:9100", control.decisions().get(0).selectedNodeId(), "대조: 그 노드의 중지가 없으면 그리로 간다");
    }

    @DisplayName("링크 원장은 한 번 읽어 모든 정책 사본에 넘기고, 링크 반영을 켠 정책의 워크로드만 끊긴 링크로 막힌다")
    @Test
    void link_ledger_blocks_only_affinity_policy() {
        nodes(node(X, 16, 10), new NodeResource("mdc-b", "far:9100", 64, 65536, 0, 5, 20, 15, 400, 1000, 40, 0, 0));
        Workload gpu = placed(1, "g", X, SCHEDULED, 1, NORMAL, 1);
        gpu.setResourceType(Workload.ResourceType.GPU);
        gpu.setDataMdcId("mdc-a");
        Workload vm = placed(2, "v", X, SCHEDULED, 1, NORMAL, 2);
        vm.setDataMdcId("mdc-a");
        held(gpu, vm);
        SchedulingPolicy affinity = policy("gpu-affinity");
        affinity.setAffinityEnabled(true);
        when(policies.findByActiveTrueAndResourceTypeOrderByNameAsc(Workload.ResourceType.GPU))
                .thenReturn(List.of(affinity));
        MdcLink down = new MdcLink("mdc-a", "mdc-b", 5);
        down.setStatus(MdcLink.LinkStatus.DOWN);
        when(links.findAll()).thenReturn(List.of(down));

        NodeDrainPreviewResponse r = service().drainPreview(X, null);

        assertEquals("{\"MDC_LINK_DOWN\":1}", r.decisions().get(0).exclusionCodes());
        assertFalse(r.decisions().get(0).success());
        assertTrue(r.decisions().get(1).success(), "대조: 링크 반영을 끈 정책은 같은 링크를 보지 않는다");
        verify(links).findAll();
    }

    @DisplayName("냉각 대기를 새로 걸지 않고, 같은 노드를 실제 평가기로 보면 걸린다")
    @Test
    void preview_does_not_mark_cooldown() {
        nodes(node(X, 16, 10), new NodeResource("mdc-a", "hot:9100", 64, 65536, 0, 5, 20, 15, 400, 1000, 70, 0, 0));
        held(placed(1, "a", X, SCHEDULED, 1, NORMAL, 1));
        SchedulingPolicy thermal = policy("thermal");
        thermal.setTemperatureAwareEnabled(true);
        thermal.setMaxTemperatureCelsius(60);
        when(policies.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()).thenReturn(List.of(thermal));

        NodeDrainPreviewResponse r = service().drainPreview(X, null);

        assertEquals("{\"TEMPERATURE_EXCEEDED\":1}", r.decisions().get(0).exclusionCodes());
        assertFalse(evaluator.anyCoolingDown());
        service().dryRun(new Workload("d", 1, 64), null);
        assertTrue(evaluator.anyCoolingDown(), "대조: 실제 평가기로 같은 노드를 보면 냉각 대기가 걸린다");
    }

    // ── 다른 노드 없음 · 상한 · 대상 · 저장 · 404 · 엔진 ────────────────

    @DisplayName("비울 노드 말고 수집된 노드가 없으면 엔진을 부르지 않고 모든 행을 실패로 둔다")
    @Test
    void no_other_node_fails_without_engine() {
        nodes(node(X, 16, 10));
        held(placed(1, "a", X, SCHEDULED, 1, NORMAL, 1), placed(2, "b", X, RUNNING, 1, NORMAL, 2));
        AtomicInteger calls = new AtomicInteger();

        NodeDrainPreviewResponse r = previewer((w, e, c) -> {
            calls.incrementAndGet();
            return SchedulingResult.failure("x");
        }).preview(X, null);

        assertEquals(0, calls.get());
        assertEquals(0, r.otherNodeCount());
        assertTrue(r.decisions().stream()
                .allMatch(d -> !d.success() && NodeDrainPreviewer.NO_OTHER_NODE.equals(d.reason())));
        assertEquals("default", r.decisions().get(0).policyName());
        assertFalse(r.allPlaceable());

        nodes();
        NodeDrainPreviewResponse none = previewer((w, e, c) -> SchedulingResult.failure("x")).preview(X, null);
        assertFalse(none.collected());
        assertEquals("활성 노드가 없습니다", none.decisions().get(0).reason(), "수집 노드가 0이면 활성 노드 없음 사유");
    }

    @DisplayName("대상이 상한을 넘으면 앞부분만 평가하고, 대상이 0건이면 allPlaceable 은 true 다")
    @Test
    void cap_truncates_and_empty_is_all_placeable() {
        nodes(node(X, 16, 10), node("big:9100", 64, 5));
        held(placed(1, "a", X, SCHEDULED, 1, NORMAL, 1), placed(2, "b", X, SCHEDULED, 1, NORMAL, 2),
                placed(3, "c", X, SCHEDULED, 1, NORMAL, 3));
        properties.setMaxWorkloadsPerRun(2);

        NodeDrainPreviewResponse r = service().drainPreview(X, null);

        assertEquals(3, r.workloadCount());
        assertEquals(List.of(1L, 2L), ids(r));
        assertEquals(2, r.placeableCount());
        assertTrue(r.truncated());
        assertFalse(r.allPlaceable(), "상한에 걸리면 모두 성공해도 false");

        held();
        NodeDrainPreviewResponse empty = service().drainPreview(X, null);
        assertEquals(0, empty.workloadCount());
        assertTrue(empty.decisions().isEmpty());
        assertTrue(empty.allPlaceable());
        assertFalse(empty.truncated());
        assertEquals(NodeDrainPreviewer.LIMITATION, empty.limitation());
    }

    @DisplayName("대상 행은 노드별 예약 내역의 그 노드 행과 같은 워크로드 · 판정이다")
    @Test
    void targets_match_node_reservation_rows() {
        nodes(node(X, 64, 5), node("big:9100", 64, 5));
        Workload expired = placed(1, "expired", X, SCHEDULED, 1, NORMAL, 1);
        ReflectionTestUtils.setField(expired, "scheduledAt", OffsetDateTime.now().minusDays(3));
        Workload gpuOnly = placed(2, "long-run", X, RUNNING, 1, NORMAL, 2);
        ReflectionTestUtils.setField(gpuOnly, "scheduledAt", OffsetDateTime.now().minusHours(2));
        Workload full = placed(3, "fresh", X, SCHEDULED, 1, NORMAL, 3);
        Workload elsewhere = placed(4, "elsewhere", "big:9100", SCHEDULED, 1, NORMAL, 4);
        Workload unassigned = placed(5, "unassigned", null, SCHEDULED, 1, NORMAL, 5);
        held(expired, gpuOnly, full, elsewhere, unassigned);
        SchedulingService service = service();

        NodeDrainPreviewResponse r = service.drainPreview(X, null);
        NodeReservationsResponse rows = service.nodeReservations(X);

        List<String> preview = r.decisions().stream()
                .map(d -> d.workloadId() + ":" + d.judgment()).sorted().toList();
        List<String> listed = rows.nodes().get(0).workloads().stream()
                .map(h -> h.workloadId() + ":" + h.judgment()).sorted().toList();
        assertEquals(List.of("1:EXPIRED", "2:GPU_ONLY", "3:FULL"), preview);
        assertEquals(listed, preview);
        assertEquals(rows.nodes().get(0).workloads().size(), r.workloadCount());
    }

    @DisplayName("아무것도 저장하지 않는다 — 정책이 하나도 없어도 default 를 저장하지 않고 워크로드 · 원장도 쓰지 않는다")
    @Test
    void writes_nothing() {
        nodes(node(X, 16, 10), node("big:9100", 64, 5));
        held(placed(1, "a", X, SCHEDULED, 1, NORMAL, 1));

        NodeDrainPreviewResponse r = service().drainPreview(X, null);

        assertEquals("default", r.decisions().get(0).policyName());
        verify(policies, never()).save(any());
        verify(workloads, never()).save(any());
        verify(workloads, never()).saveAll(anyList());
        verify(holds, never()).save(any());
    }

    @DisplayName("수집 후보 · 수집 제외 · 배정 워크로드 · 중지 원장 어디에도 없는 노드만 404 다")
    @Test
    void unknown_node_is_404_but_any_source_is_200() {
        nodes(node("big:9100", 64, 5));
        BusinessException e = assertThrows(BusinessException.class, () -> service().drainPreview("ghost:9100", null));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());

        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(node("big:9100", 64, 5)), List.of(new ExcludedNode("mdc-a", "ghost:9100", List.of("cpu")))));
        NodeDrainPreviewResponse excluded = service().drainPreview("ghost:9100", null);
        assertFalse(excluded.collected());
        assertEquals(0, excluded.workloadCount());

        nodes(node("big:9100", 64, 5));
        held(placed(1, "a", "ghost:9100", SCHEDULED, 1, NORMAL, 1));
        NodeDrainPreviewResponse assigned = service().drainPreview("ghost:9100", null);
        assertFalse(assigned.collected());
        assertEquals("big:9100", assigned.decisions().get(0).selectedNodeId());

        held();
        when(holds.findAll()).thenReturn(List.of(new NodePlacementHold("ghost:9100")));
        assertTrue(service().drainPreview("ghost:9100", null).held());
    }

    @DisplayName("요청이 비고 정책 기본 엔진이 등록되지 않았으면 서버 기본값을 쓰고, 모르는 엔진은 400 이다")
    @Test
    void engine_is_requested_or_policy_default() {
        nodes(node(X, 16, 10), node("big:9100", 64, 5));
        held(placed(1, "a", X, SCHEDULED, 1, NORMAL, 1));
        SchedulingPolicy unregistered = policy("p");
        unregistered.setDefaultEngine("power");
        when(policies.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()).thenReturn(List.of(unregistered));

        assertEquals("weighted", service().drainPreview(X, null).decisions().get(0).engine(),
                "등록되지 않은 정책 기본 엔진은 서버 기본값");
        assertThrows(BusinessException.class, () -> service().drainPreview(X, "nope"));
    }

    // ── 조립기 ────────────────────────────────────────────────

    @DisplayName("비우기 조립은 같은 입력의 배치 조립과 노드 예약 · 원장이 같고, 링크는 정책과 관계없이 싣는다")
    @Test
    void drain_base_matches_create_context() {
        nodes(node(X, 16, 10), node("big:9100", 64, 5));
        held(placed(1, "a", X, SCHEDULED, 2, NORMAL, 1), placed(2, "b", "big:9100", RUNNING, 3, NORMAL, 2));
        when(holds.findAll()).thenReturn(List.of(new NodePlacementHold("big:9100")));
        when(budgets.findAll()).thenReturn(List.of(new MdcPowerBudget("mdc-a", 900)));
        when(links.findAll()).thenReturn(List.of(new MdcLink("mdc-a", "mdc-b", 5)));
        PlacementContextAssembler assembler = assembler();
        SchedulingPolicy plain = policy("plain");
        SchedulingPolicy affinity = policy("affinity");
        affinity.setAffinityEnabled(true);

        PlacementContextAssembler.DrainBase base = assembler.drainBase(OffsetDateTime.now());
        PlacementContext real = assembler.createContext(affinity);

        assertEquals(reserved(real), reserved(base.context()));
        assertEquals(real.placementHolds(), base.context().placementHolds());
        assertEquals(real.mdcBudgets(), base.context().mdcBudgets());
        assertEquals(real.linkMatrix().entries(), base.context().linkMatrix().entries());
        assertEquals(1, base.context().linkMatrix().size());
        assertTrue(assembler.createContext(plain).linkMatrix().isEmpty(),
                "대조: 링크 반영을 끈 정책의 배치 조립은 링크를 읽지 않는다");
        assertNull(base.context().policy());
        assertEquals(2, base.reading().held().size());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** 정책 저장소는 받은 객체를 그대로 돌려준다 — 저장 여부만 다르고 배치 결과는 같게 해 「저장 0」만 갈리게 한다. */
    private SchedulingService service() {
        when(policies.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return SchedulingServiceBuilder.aSchedulingService()
                .collector(collector).workloads(workloads).policies(policies).holds(holds).links(links).budgets(budgets)
                .evaluator(evaluator).properties(properties).build();
    }

    private PlacementContextAssembler assembler() {
        return new PlacementContextAssembler(collector, evaluator, workloads, properties, links, budgets, null, holds);
    }

    private NodeDrainPreviewer previewer(NodeDrainPreviewer.Evaluation evaluation) {
        return new NodeDrainPreviewer(assembler(), evaluator, new PolicyResolver(policies)::choose, evaluation,
                new EngineRegistry(List.of(new WeightedScoreScheduler())), properties);
    }

    /** 호출마다 새 노드 객체 — 예약은 노드 객체에 쌓이므로 같은 객체를 돌려주면 앞 호출의 예약이 남는다. */
    private void nodes(NodeResource... nodes) {
        List<NodeResource> template = List.of(nodes);
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                template.stream().map(NodeResource::snapshot).toList(), List.of()));
    }

    private void held(Workload... held) {
        List<Workload> rows = List.of(held);
        when(workloads.findByStatusIn(anyList())).thenAnswer(inv -> new ArrayList<>(rows));
    }

    private static NodeResource node(String id, int cpu, double cpuUsage) {
        return new NodeResource("mdc-a", id, cpu, 65536, 0, cpuUsage, 20, 15, 400, 1000, 40, 0, 0);
    }

    private static SchedulingPolicy policy(String name) {
        SchedulingPolicy p = new SchedulingPolicy(name, "d");
        p.setActive(true);
        return p;
    }

    /** id · 상태 · 등록 순서(분)만 채운 배치된 워크로드. 메모리 512MB. 배정 노드가 null이면 배정 없는 배치 대기. */
    private static Workload placed(long id, String name, String node, Workload.WorkloadStatus status, int cpu,
                                   Workload.WorkloadPriority priority, int createdMinute) {
        Workload w = new Workload(name, cpu, 512);
        w.setPriority(priority);
        w.assignTo(node == null ? null : "mdc-a", node, 0.5, "test");
        ReflectionTestUtils.setField(w, "id", id);
        ReflectionTestUtils.setField(w, "createdAt",
                OffsetDateTime.parse("2026-11-01T00:00:00Z").plusMinutes(createdMinute));
        if (status == RUNNING) ReflectionTestUtils.setField(w, "status", RUNNING);
        return w;
    }

    private static List<Long> ids(NodeDrainPreviewResponse r) {
        return r.decisions().stream().map(Decision::workloadId).toList();
    }

    private static Map<String, List<Long>> reserved(PlacementContext ctx) {
        return ctx.nodes().stream().collect(Collectors.toMap(NodeResource::getNodeId,
                n -> List.of((long) n.getReservedCpu(), n.getReservedMemoryMb(), (long) n.getReservedGpu(),
                        n.reservedDiskGb())));
    }
}

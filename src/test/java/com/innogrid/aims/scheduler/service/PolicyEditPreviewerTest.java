package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PolicyDryRunResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.MdcPowerBudget;
import com.innogrid.aims.scheduler.entity.NodePlacementHold;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.MdcLinkRepository;
import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;
import com.innogrid.aims.scheduler.repository.NodePlacementHoldRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 정책 수정 전 모의 배치 — 수정 없음이면 두 쪽 같음, 사용률 · 온도 상한 · 가중치 · 전력 스위치 · 기본 엔진 · 링크 반영 수정의 효과,
 * 두 쪽이 같은 원장(배치 중지 · 기존 예약 · MDC 전력 예산)을 봄, 수집 1회, 수집 후보 0, 저장 0, 저장된 정책 · 수집 노드 그대로,
 * 지금 배치에 쓰는 정책인지, 달라진 필드 이름의 순서와 범위, 단건 평가({@code evaluateIn})와 같은 결정.
 *
 * <p>노드는 따로 적지 않으면 전력 · 온도를 모르는 값(0)이라 C1 자원 · C2 사용률만 걸린다. 수정의 효과를 보는 테스트에는 그 필드를
 * 저장된 값 그대로 둔 대조를 함께 둔다. 스프링 없이 돈다.</p>
 */
class PolicyEditPreviewerTest {

    private static final String A = "a:9100";
    private static final String B = "b:9100";

    private final NodeResourceCollector collector = mock(NodeResourceCollector.class);
    private final WorkloadRepository workloads = mock(WorkloadRepository.class);
    private final SchedulingPolicyRepository policies = mock(SchedulingPolicyRepository.class);
    private final NodePlacementHoldRepository holds = mock(NodePlacementHoldRepository.class);
    private final MdcLinkRepository links = mock(MdcLinkRepository.class);
    private final MdcPowerBudgetRepository budgets = mock(MdcPowerBudgetRepository.class);
    private final SchedulerProperties properties = new SchedulerProperties();
    private final ConstraintEvaluator evaluator = ConstraintEvaluator.withDefaults();
    private EngineRegistry engines = new EngineRegistry(List.of(new WeightedScoreScheduler()));

    // ── 수정 없음 · 상한 ────────────────────────────────────────

    @DisplayName("수정이 없으면 두 쪽의 결과 · 엔진이 같고 달라진 필드도 결정 변화도 없다")
    @Test
    void no_changes_give_same_results() {
        nodes(node(A, 8, 10), node(B, 4, 0));
        SchedulingPolicy saved = saved("p", 1L);

        PolicyDryRunResponse r = service().policyEditPreview(1L, saved, saved.detachedCopy(), draft(2, 512), null);

        assertTrue(r.current().success());
        assertEquals(r.current().selectedNodeId(), r.edited().selectedNodeId());
        assertEquals(r.current().finalScore(), r.edited().finalScore());
        assertFalse(r.decisionChanged());
        assertEquals(List.of(), r.changedFields());
        assertEquals("weighted", r.currentEngine());
        assertEquals("weighted", r.editedEngine());
        assertEquals("p", r.policyName());
        assertEquals("p", r.edited().policyName());
        assertEquals(Workload.ResourceType.VM, r.resourceType());
        assertEquals(PolicyEditPreviewer.LIMITATION, r.limitation());
    }

    @DisplayName("CPU 사용률 상한을 사용률 아래로 고치면 고친 쪽만 CPU_USAGE_EXCEEDED 로 빠지고, 위로 고치면 결정이 그대로다")
    @Test
    void cpu_limit_below_usage_excludes_only_edited() {
        nodes(node(A, 8, 30));
        SchedulingPolicy saved = saved("p", 1L);
        SchedulingPolicy edited = saved.detachedCopy();
        edited.setMaxCpuUsagePercent(20);

        PolicyDryRunResponse r = service().policyEditPreview(1L, saved, edited, draft(1, 512), null);

        assertTrue(r.current().success());
        assertFalse(r.edited().success());
        assertEquals(ExclusionReason.CPU_USAGE_EXCEEDED, r.edited().candidateScores().get(0).exclusionCode());
        assertTrue(r.decisionChanged());
        assertEquals(List.of("maxCpuUsagePercent"), r.changedFields());

        SchedulingPolicy loose = saved.detachedCopy();
        loose.setMaxCpuUsagePercent(40);
        PolicyDryRunResponse control = service().policyEditPreview(1L, saved, loose, draft(1, 512), null);
        assertTrue(control.edited().success(), "대조: 사용률 30% 보다 높은 상한이면 들어간다");
        assertFalse(control.decisionChanged());
        assertEquals(List.of("maxCpuUsagePercent"), control.changedFields(), "값이 다르면 결정이 같아도 달라진 필드에 든다");
    }

    @DisplayName("메모리 사용률 상한을 사용률 아래로 고치면 고친 쪽만 MEMORY_USAGE_EXCEEDED 로 빠진다")
    @Test
    void memory_limit_below_usage_excludes_only_edited() {
        nodes(new NodeResource("mdc-a", A, 8, 65536, 0, 10, 50, 15, 0, 0, 0, 0, 0));
        SchedulingPolicy saved = saved("p", 1L);
        SchedulingPolicy edited = saved.detachedCopy();
        edited.setMaxMemoryUsagePercent(40);

        PolicyDryRunResponse r = service().policyEditPreview(1L, saved, edited, draft(1, 512), null);

        assertTrue(r.current().success());
        assertEquals(ExclusionReason.MEMORY_USAGE_EXCEEDED, r.edited().candidateScores().get(0).exclusionCode());
        assertEquals(List.of("maxMemoryUsagePercent"), r.changedFields());
    }

    @DisplayName("온도 상한을 노드 온도 아래로 고치면 고친 쪽만 TEMPERATURE_EXCEEDED 로 빠지고 냉각 대기는 걸지 않는다")
    @Test
    void temperature_limit_excludes_without_marking_cooldown() {
        nodes(new NodeResource("mdc-a", "hot:9100", 64, 65536, 0, 5, 20, 15, 0, 0, 70, 0, 0));
        SchedulingPolicy saved = saved("p", 1L);
        SchedulingPolicy edited = saved.detachedCopy();
        edited.setMaxTemperatureCelsius(60);

        PolicyDryRunResponse r = service().policyEditPreview(1L, saved, edited, draft(1, 512), null);

        assertTrue(r.current().success(), "저장된 상한 75 아래라 들어간다");
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, r.edited().candidateScores().get(0).exclusionCode());
        assertFalse(evaluator.anyCoolingDown());
        when(policies.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()).thenReturn(List.of(edited));
        service().dryRun(draft(1, 512), null);
        assertTrue(evaluator.anyCoolingDown(), "대조: 같은 정책으로 저장 전 모의 배치를 하면 냉각 대기가 걸린다");
    }

    // ── 가중치 · 스위치 · 기본 엔진 · 링크 ──────────────────────

    @DisplayName("가중치를 CPU 에서 메모리로 옮기면 고르는 노드가 바뀐다")
    @Test
    void weights_change_the_choice() {
        // A: CPU 여유 · 메모리 빠듯 / B: CPU 빠듯 · 메모리 여유
        nodes(new NodeResource("mdc-a", A, 8, 65536, 0, 10, 80, 15, 0, 0, 0, 0, 0),
                new NodeResource("mdc-a", B, 8, 65536, 0, 70, 10, 15, 0, 0, 0, 0, 0));
        SchedulingPolicy saved = saved("p", 1L);
        onlyWeight(saved, "cpu");
        SchedulingPolicy edited = saved.detachedCopy();
        onlyWeight(edited, "memory");

        PolicyDryRunResponse r = service().policyEditPreview(1L, saved, edited, draft(1, 512), null);

        assertEquals(A, r.current().selectedNodeId());
        assertEquals(B, r.edited().selectedNodeId());
        assertTrue(r.decisionChanged());
        assertEquals(List.of("cpuWeight", "memoryWeight"), r.changedFields());
    }

    @DisplayName("전력 인지를 끄면 전력 여유가 모자란 노드가 고친 쪽에서만 들어간다")
    @Test
    void power_switch_changes_eligibility() {
        nodes(new NodeResource("mdc-a", A, 8, 65536, 0, 10, 20, 15, 950, 1000, 0, 0, 0));
        SchedulingPolicy saved = saved("p", 1L);
        SchedulingPolicy edited = saved.detachedCopy();
        edited.setPowerAwareEnabled(false);

        PolicyDryRunResponse r = service().policyEditPreview(1L, saved, edited, draft(1, 512), null);

        assertEquals(ExclusionReason.POWER_HEADROOM_EXCEEDED, r.current().candidateScores().get(0).exclusionCode(),
                "저장된 정책: 전력 950W / 정격 1000W 라 여유 5% < 10%");
        assertTrue(r.edited().success());
        assertEquals(List.of("powerAwareEnabled"), r.changedFields());
    }

    @DisplayName("기본 엔진을 고치면 요청 엔진이 없을 때 고친 쪽만 그 엔진을 쓰고, 요청 엔진이 있으면 두 쪽에 같게 쓴다")
    @Test
    void default_engine_edit_and_requested_engine() {
        engines = new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler()));
        nodes(node(A, 8, 10));
        SchedulingPolicy saved = saved("p", 1L);
        SchedulingPolicy edited = saved.detachedCopy();
        edited.setDefaultEngine("power");

        PolicyDryRunResponse r = service().policyEditPreview(1L, saved, edited, draft(1, 512), null);
        assertEquals("weighted", r.currentEngine());
        assertEquals("power", r.editedEngine());
        assertEquals(List.of("defaultEngine"), r.changedFields());

        PolicyDryRunResponse requested = service().policyEditPreview(1L, saved, edited, draft(1, 512), "weighted");
        assertEquals("weighted", requested.currentEngine());
        assertEquals("weighted", requested.editedEngine());
    }

    @DisplayName("링크 반영을 켠 쪽만 끊긴 링크 건너 노드를 빼고, 링크 원장은 한 번 읽는다")
    @Test
    void affinity_edit_applies_link_only_to_edited() {
        nodes(node(A, 8, 60), new NodeResource("mdc-b", "far:9100", 64, 65536, 0, 0, 20, 15, 0, 0, 0, 0, 0));
        MdcLink down = new MdcLink("mdc-a", "mdc-b", 5);
        down.setStatus(MdcLink.LinkStatus.DOWN);
        when(links.findAll()).thenReturn(List.of(down));
        SchedulingPolicy saved = saved("p", 1L);
        SchedulingPolicy edited = saved.detachedCopy();
        edited.setAffinityEnabled(true);
        Workload d = draft(1, 512);
        d.setDataMdcId("mdc-a");

        PolicyDryRunResponse r = service().policyEditPreview(1L, saved, edited, d, null);

        assertEquals("far:9100", r.current().selectedNodeId(), "링크 반영을 끈 정책은 여유가 큰 먼 노드를 고른다");
        assertEquals(A, r.edited().selectedNodeId());
        assertTrue(r.edited().candidateScores().stream()
                .anyMatch(s -> "far:9100".equals(s.nodeId()) && s.exclusionCode() == ExclusionReason.MDC_LINK_DOWN));
        assertTrue(r.decisionChanged());
        verify(links, times(1)).findAll();
    }

    // ── 같은 원장 · 수집 · 저장 ─────────────────────────────────

    @DisplayName("배치 중지 · 기존 예약은 두 쪽에 같게 걸리고, 둘이 없으면 같은 초안이 들어간다")
    @Test
    void ledgers_apply_to_both_sides() {
        nodes(node(A, 8, 0), node(B, 8, 0));
        when(holds.findAll()).thenReturn(List.of(new NodePlacementHold(B)));
        held(placed(1, A, 6));
        SchedulingPolicy saved = saved("p", 1L);
        SchedulingPolicy edited = saved.detachedCopy();
        edited.setNetworkWeight(0.5);

        PolicyDryRunResponse r = service().policyEditPreview(1L, saved, edited, draft(4, 512), null);

        // A: 8 − 6 = 2 코어 < 4 · B: 배치 중지
        assertFalse(r.current().success());
        assertFalse(r.edited().success());
        assertEquals(PlacementService.exclusionCodesOf(r.current()), PlacementService.exclusionCodesOf(r.edited()));
        assertEquals("{\"INSUFFICIENT_RESOURCE\":1,\"NODE_PLACEMENT_HOLD\":1}",
                PlacementService.exclusionCodesOf(r.edited()));
        assertFalse(r.decisionChanged());

        when(holds.findAll()).thenReturn(List.of());
        held();
        PolicyDryRunResponse control = service().policyEditPreview(1L, saved, edited, draft(4, 512), null);
        assertTrue(control.current().success() && control.edited().success(), "대조: 중지 · 예약이 없으면 들어간다");
    }

    @DisplayName("MDC 전력 예산은 두 쪽에 같게 걸리고, 예산이 없으면 같은 초안이 들어간다")
    @Test
    void mdc_budget_applies_to_both_sides() {
        // 두 노드 모두 전력 400W 계측 · 같은 MDC 예산 100W — 어느 노드에 놓아도 MDC 합계가 예산을 넘는다
        nodes(new NodeResource("mdc-a", A, 16, 65536, 0, 5, 20, 15, 400, 1000, 0, 0, 0),
                new NodeResource("mdc-a", B, 16, 65536, 0, 5, 20, 15, 400, 1000, 0, 0, 0));
        when(budgets.findAll()).thenReturn(List.of(new MdcPowerBudget("mdc-a", 100)));
        SchedulingPolicy saved = saved("p", 1L);
        SchedulingPolicy edited = saved.detachedCopy();
        edited.setNetworkWeight(0.5);

        PolicyDryRunResponse r = service().policyEditPreview(1L, saved, edited, draft(1, 512), null);

        assertFalse(r.current().success());
        assertFalse(r.edited().success());
        assertEquals("{\"MDC_POWER_BUDGET_EXCEEDED\":2}", PlacementService.exclusionCodesOf(r.current()));
        assertEquals("{\"MDC_POWER_BUDGET_EXCEEDED\":2}", PlacementService.exclusionCodesOf(r.edited()));
        assertFalse(r.decisionChanged());

        when(budgets.findAll()).thenReturn(List.of());
        PolicyDryRunResponse control = service().policyEditPreview(1L, saved, edited, draft(1, 512), null);
        assertTrue(control.current().success() && control.edited().success(), "대조: 예산이 없으면 들어간다");
    }

    @DisplayName("메트릭은 한 번만 수집한다")
    @Test
    void collects_once() {
        nodes(node(A, 8, 10));
        SchedulingPolicy saved = saved("p", 1L);
        SchedulingPolicy edited = saved.detachedCopy();
        edited.setMaxCpuUsagePercent(50);

        service().policyEditPreview(1L, saved, edited, draft(1, 512), null);

        verify(collector, times(1)).collect();
    }

    @DisplayName("수집 후보가 없으면 엔진을 부르지 않고 두 쪽 모두 활성 노드가 없다는 사유다")
    @Test
    void no_collected_node_skips_engine() {
        nodes();
        AtomicInteger calls = new AtomicInteger();
        engines = new EngineRegistry(List.of(counting(new WeightedScoreScheduler(), calls)));
        SchedulingPolicy saved = saved("p", 1L);

        PolicyDryRunResponse r = service().policyEditPreview(1L, saved, saved.detachedCopy(), draft(1, 512), null);

        assertEquals(0, calls.get());
        assertEquals("활성 노드가 없습니다", r.current().reason());
        assertEquals("활성 노드가 없습니다", r.edited().reason());
        assertEquals("p", r.edited().policyName());
        assertFalse(r.decisionChanged());
    }

    @DisplayName("아무것도 저장하지 않고, 저장된 정책 값과 수집한 노드 객체도 그대로 둔다")
    @Test
    void saves_nothing_and_touches_nothing() {
        NodeResource a = node(A, 8, 10);
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(a), List.of()));
        held(placed(1, A, 2));
        SchedulingPolicy saved = saved("p", 1L);
        SchedulingPolicy edited = saved.detachedCopy();
        edited.setMaxCpuUsagePercent(50);

        PolicyDryRunResponse r = service().policyEditPreview(1L, saved, edited, draft(1, 512), null);

        assertTrue(r.current().success());
        assertEquals(80.0, saved.getMaxCpuUsagePercent(), "저장된 정책 값 그대로");
        assertEquals(2, a.getReservedCpu(), "수집 노드에는 기존 예약만 있고 초안을 쌓지 않는다");
        verify(policies, never()).save(any());
        verify(workloads, never()).save(any());
        verify(workloads, never()).saveAll(anyList());
        verify(holds, never()).save(any());
    }

    // ── 지금 배치에 쓰는 정책 · 달라진 필드 · 단건 평가 ────────────

    @DisplayName("지금 배치에 쓰는 정책이면 policyInUse 는 true — 활성 공통 정책 · 활성이 없어 쓰는 default 행 — 이고 다른 정책이면 false")
    @Test
    void policy_in_use_follows_policy_choice() {
        nodes(node(A, 8, 10));
        SchedulingPolicy saved = saved("p", 1L);
        when(policies.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()).thenReturn(List.of(saved));
        assertTrue(service().policyEditPreview(1L, saved, saved.detachedCopy(), draft(1, 512), null).policyInUse());

        SchedulingPolicy other = saved("other", 2L);
        when(policies.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()).thenReturn(List.of(other));
        assertFalse(service().policyEditPreview(1L, saved, saved.detachedCopy(), draft(1, 512), null).policyInUse(),
                "다른 정책이 활성이면 이 결과는 지금 배치와 무관");

        SchedulingPolicy defaultRow = saved("default", 3L);
        defaultRow.setActive(false);
        when(policies.findByActiveTrueAndResourceTypeIsNullOrderByNameAsc()).thenReturn(List.of());
        when(policies.findByName("default")).thenReturn(Optional.of(defaultRow));
        assertTrue(service().policyEditPreview(3L, defaultRow, defaultRow.detachedCopy(), draft(1, 512), null)
                        .policyInUse(),
                "활성이 없으면 배치가 default 행 값을 쓴다");
    }

    @DisplayName("달라진 필드는 고정 순서의 판단 필드만이고 종류 · 활성 · 이름 · 설명은 넣지 않는다")
    @Test
    void changed_fields_are_ordered_judgment_fields() {
        SchedulingPolicy saved = saved("p", 1L);
        SchedulingPolicy edited = saved.detachedCopy();
        edited.setName("renamed");
        edited.setDescription("other description");
        edited.setResourceType(Workload.ResourceType.GPU);
        edited.setActive(!saved.isActive());
        edited.setAffinityEnabled(true);
        edited.setNetworkWeight(0.9);
        edited.setDefaultEngine("weighted");

        assertEquals(List.of("defaultEngine", "networkWeight", "affinityEnabled"),
                PolicyEditPreviewer.changedFields(saved, edited));
    }

    @DisplayName("두 쪽의 결정은 같은 정책으로 만든 배치 컨텍스트에서 단건 평가(evaluateIn)한 결정과 같다")
    @Test
    void matches_evaluate_in() {
        nodes(node(B, 4, 0), node(A, 8, 30), node("c:9100", 6, 10));
        held(placed(1, A, 1));
        SchedulingService service = service();
        SchedulingPolicy saved = saved("p", 1L);
        SchedulingPolicy edited = saved.detachedCopy();
        onlyWeight(edited, "memory");
        Workload d = draft(2, 2048);

        PolicyDryRunResponse r = service.policyEditPreview(1L, saved, edited, d, null);

        SchedulingResult current = service.evaluateIn(d, "weighted", service.createContext(saved));
        SchedulingResult changed = service.evaluateIn(d, "weighted", service.createContext(edited));
        assertEquals(current.selectedNodeId(), r.current().selectedNodeId());
        assertEquals(current.finalScore(), r.current().finalScore());
        assertEquals(current.reason(), r.current().reason());
        assertEquals(changed.selectedNodeId(), r.edited().selectedNodeId());
        assertEquals(changed.finalScore(), r.edited().finalScore());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private SchedulingService service() {
        when(policies.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return SchedulingServiceBuilder.aSchedulingService()
                .collector(collector).workloads(workloads).policies(policies).holds(holds).links(links).budgets(budgets)
                .evaluator(evaluator).properties(properties).engines(engines).build();
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

    /** 전력 · 온도를 모르는 노드(0) — C1 자원 · C2 사용률만 걸린다. 메모리 65536MB · 사용률 20%. */
    private static NodeResource node(String id, int cpu, double cpuUsage) {
        return new NodeResource("mdc-a", id, cpu, 65536, 0, cpuUsage, 20, 15, 0, 0, 0, 0, 0);
    }

    private static Workload draft(int cpu, int memoryMb) {
        return new Workload("draft", cpu, memoryMb);
    }

    /** 저장된 정책처럼 id 를 채운 활성 공통 정책(기본값 그대로). */
    private static SchedulingPolicy saved(String name, long id) {
        SchedulingPolicy p = new SchedulingPolicy(name, "d");
        p.setActive(true);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    /** 가중치를 한 항목에만 준다. */
    private static void onlyWeight(SchedulingPolicy p, String which) {
        p.setCpuWeight("cpu".equals(which) ? 1 : 0);
        p.setMemoryWeight("memory".equals(which) ? 1 : 0);
        p.setPowerWeight(0);
        p.setTemperatureWeight(0);
        p.setNetworkWeight(0);
    }

    /** 그 노드에 배정된 배치 대기(메모리 512MB · 방금 배정). */
    private static Workload placed(long id, String node, int cpu) {
        Workload w = new Workload("held-" + id, cpu, 512);
        w.assignTo("mdc-a", node, 0.5, "test");
        ReflectionTestUtils.setField(w, "id", id);
        ReflectionTestUtils.setField(w, "createdAt", OffsetDateTime.parse("2026-11-01T00:00:00Z"));
        return w;
    }

    /** 엔진 호출 수를 세는 감싸개. */
    private static SchedulingEngine counting(SchedulingEngine inner, AtomicInteger calls) {
        return new SchedulingEngine() {
            @Override
            public SchedulingResult schedule(Workload workload, PlacementContext context) {
                calls.incrementAndGet();
                return inner.schedule(workload, context);
            }

            @Override
            public String getName() { return inner.getName(); }

            @Override
            public String getId() { return inner.getId(); }
        };
    }
}

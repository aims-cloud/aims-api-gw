package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.ConsolidationResponse;
import com.innogrid.aims.scheduler.dto.ConsolidationResponse.Candidate;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 워크로드 통합 가능성 진단.
 *
 * <p>노드는 8코어 · 용량 500W(유휴 150W)로 맞춘다. 2코어 워크로드를 가동 중인 노드로 옮기면
 * 동적 증가분이 87.5W 이므로 손으로 검산할 수 있다.</p>
 */
class ConsolidationServiceTest {

    private final SchedulerProperties properties = new SchedulerProperties();
    private final PowerModel powerModel = new PowerModel(properties);
    private final SchedulingService schedulingService = mock(SchedulingService.class);
    private final WorkloadRepository workloadRepository = mock(WorkloadRepository.class);
    private final PlacementService placementService = mock(PlacementService.class);

    private final ConsolidationService service = new ConsolidationService(
            schedulingService, workloadRepository,
            new EngineRegistry(List.of(new PowerAwareScheduler())),
            powerModel, properties);

    // ── 순절감 판정 ────────────────────────────────────────────

    @Test
    void 배치된_워크로드가_없으면_그대로_반납한다() {
        given(List.of(node("idle", 1, 5, 150), node("busy", 50, 50, 260)), List.of());

        Candidate idle = find(service.diagnose(), "idle");

        assertTrue(idle.reclaimable());
        assertEquals("ALREADY_EMPTY", idle.basis());
        assertEquals(150.0, idle.netSavingWatts(), 1e-9);
        assertEquals(0, idle.workloadCount());
    }

    @Test
    void 옮겨서_이득이면_반납_후보다() {
        NodeResource idle = node("idle", 2, 10, 150);
        NodeResource host = node("host", 40, 40, 250);
        given(List.of(idle, host), List.of(workload(1L, "w1", 1)));

        Candidate c = find(service.diagnose(), "idle");

        assertTrue(c.reclaimable(), c.blockedReason());
        assertEquals("NET_SAVING", c.basis());
        assertEquals(150.0, c.idlePowerWatts(), 1e-9);
        // 8코어 중 1코어 = 0.125 → 350 × 0.125 = 43.75W
        assertEquals(43.75, c.relocationCostWatts(), 1e-4);
        assertEquals(106.25, c.netSavingWatts(), 1e-4);
        assertEquals(1, c.moves().size());
        assertEquals("host", c.moves().get(0).toNodeId());
    }

    @Test
    void 옮기는_비용이_더_크면_후보가_아니다() {
        // 유휴 전력이 작은 노드(용량 200W → 유휴 60W)에서 큰 워크로드를 빼면 옮기는 값이 더 크다
        NodeResource small = node("small", 3, 10, 60, 200);
        NodeResource host = node("host", 40, 40, 250, 500);
        given(List.of(small, host), List.of(workload(1L, "big", 4)));

        Candidate c = find(service.diagnose(), "small");

        assertFalse(c.reclaimable());
        assertNotNull(c.blockedReason());
        assertTrue(c.blockedReason().contains("손해"), c.blockedReason());
    }

    @Test
    void 받을_노드가_없으면_사유와_함께_불가로_본다() {
        NodeResource idle = node("idle", 2, 10, 150);
        NodeResource full = node("full", 79, 40, 300);   // 1코어만 남아 4코어를 못 받는다
        given(List.of(idle, full), List.of(workload(1L, "big", 4)));

        Candidate c = find(service.diagnose(), "idle");

        assertFalse(c.reclaimable());
        assertTrue(c.blockedReason().contains("받을 노드가 없습니다"), c.blockedReason());
    }

    @Test
    void 옮길_다른_노드가_없으면_불가로_본다() {
        NodeResource only = node("only", 2, 10, 150);
        given(List.of(only), List.of(workload(1L, "w1", 1)));

        Candidate c = find(service.diagnose(), "only");

        assertFalse(c.reclaimable());
        assertTrue(c.blockedReason().contains("옮길 다른 노드가 없습니다"), c.blockedReason());
    }

    /**
     * 스케줄러가 아는 워크로드가 없다고 노드가 빈 것은 아니다. 수동 배치나 다른 시스템의
     * 부하는 메트릭에만 보인다. 이 검사를 빠뜨리면 가동 중인 노드를 반납하라고 권고한다 —
     * 배포 VM 이 정확히 이 상태다(워크로드 0건, CPU 12%).
     */
    @Test
    void 워크로드가_없어도_부하가_있으면_반납하지_않는다() {
        given(List.of(node("busy-but-unmanaged", 12, 30, 200), node("host", 30, 30, 240)), List.of());

        Candidate c = find(service.diagnose(), "busy-but-unmanaged");

        assertFalse(c.reclaimable(), "CPU 12% 로 무언가 돌고 있다");
        assertTrue(c.blockedReason().contains("다른 부하가 돌고 있습니다"), c.blockedReason());
    }

    /** 옮길 워크로드를 다 빼도 부하가 남으면 반납할 수 없다. */
    @Test
    void 워크로드를_다_옮겨도_부하가_남으면_반납하지_않는다() {
        // CPU 40% 인데 옮기는 워크로드는 1코어(12.5%p) 뿐 → 27.5% 가 남는다
        NodeResource node = node("partly-managed", 40, 30, 250);
        NodeResource host = node("host", 20, 20, 210);
        given(List.of(node, host), List.of(workload(1L, "w1", 1)));

        Candidate c = find(service.diagnose(), "partly-managed");

        assertFalse(c.reclaimable());
        assertTrue(c.blockedReason().contains("부하가 남아"), c.blockedReason());
    }

    // ── 상한 ──────────────────────────────────────────────────

    /** 60건 중 50건만 옮겨보고 "반납 가능" 이라 하면 거짓이다. */
    @Test
    void 워크로드가_상한을_넘으면_평가하지_않는다() {
        properties.getPower().setMaxRelocationsPerNode(2);

        NodeResource idle = node("idle", 2, 10, 150);
        NodeResource host = node("host", 30, 30, 240);
        given(List.of(idle, host), List.of(
                workload(1L, "w1", 1), workload(2L, "w2", 1), workload(3L, "w3", 1)));

        Candidate c = find(service.diagnose(), "idle");

        assertFalse(c.evaluated(), "판정하지 않아야 한다");
        assertFalse(c.reclaimable());
        assertTrue(c.skipReason().contains("상한 2건"), c.skipReason());
        assertEquals(3, c.workloadCount());
    }

    @Test
    void 후보_상한을_넘으면_잘림을_표시한다() {
        properties.getPower().setMaxConsolidationCandidates(1);

        given(List.of(node("a", 2, 10, 150), node("b", 3, 10, 150), node("c", 4, 10, 150)), List.of());

        ConsolidationResponse result = service.diagnose();

        assertEquals(1, result.candidates().stream().filter(Candidate::evaluated).count());
        assertTrue(result.summary().truncated());
        assertNotNull(result.summary().truncatedReason());
        assertTrue(result.summary().truncatedReason().contains("2대"), result.summary().truncatedReason());
    }

    /** 상한에 걸려도 절감이 큰 후보가 먼저 평가돼야 한다. */
    @Test
    void 절감_잠재량이_큰_후보부터_평가한다() {
        properties.getPower().setMaxConsolidationCandidates(1);

        // 용량이 클수록 유휴 전력이 크다 → 먼저 평가돼야 한다
        given(List.of(node("small", 2, 10, 60, 200), node("large", 2, 10, 240, 800)), List.of());

        Candidate evaluated = service.diagnose().candidates().stream()
                .filter(Candidate::evaluated).findFirst().orElseThrow();

        assertEquals("large", evaluated.nodeId());
    }

    // ── 부작용 방어 ────────────────────────────────────────────

    /** S1 — 진단은 계산일 뿐이다. 실제 배치를 일으키면 안 된다. */
    @Test
    void 시뮬레이션이_실제_배치를_일으키지_않는다() {
        given(List.of(node("idle", 2, 10, 150), node("host", 40, 40, 250)),
                List.of(workload(1L, "w1", 1)));

        service.diagnose();

        verify(placementService, never()).place(any(), any(), anyString());
        verify(workloadRepository, never()).save(any());
    }

    /** S2 — NodeResource 는 가변이다. 조회용 스냅샷에 예약이 남으면 응답이 오염된다. */
    @Test
    void 시뮬레이션_후_조회용_노드에_예약이_남지_않는다() {
        NodeResource idle = node("idle", 2, 10, 150);
        NodeResource host = node("host", 40, 40, 250);
        given(List.of(idle, host), List.of(workload(1L, "w1", 1), workload(2L, "w2", 1)));

        service.diagnose();

        assertEquals(0, idle.getReservedCpu(), "진단이 조회용 노드를 더럽혔다");
        assertEquals(0, host.getReservedCpu(), "진단이 조회용 노드를 더럽혔다");
    }

    /**
     * 후보마다 메트릭을 다시 수집하면 노드당 아홉 종의 질의가 후보 수만큼 되풀이된다.
     * 메트릭 클라이언트에는 읽기 타임아웃이 없어 저장소가 느려지면 요청이 오래 붙잡힌다.
     */
    @Test
    void 후보가_여럿이어도_메트릭은_한_번만_수집한다() {
        given(List.of(node("a", 2, 10, 150), node("b", 3, 10, 150), node("c", 4, 10, 150)),
                List.of(workload(1L, "w1", 1)));

        service.diagnose();

        verify(schedulingService, times(1)).createContext(any());
    }

    /** 샌드박스는 사본이라 한 후보의 예약이 다음 후보 판정에 섞이면 안 된다. */
    @Test
    void 앞_후보의_예약이_다음_후보_판정에_섞이지_않는다() {
        NodeResource a = node("a", 2, 10, 150);
        NodeResource b = node("b", 2, 10, 150);
        NodeResource host = node("host", 30, 30, 240);
        given(List.of(a, b, host), List.of(workload(1L, "w1", 1)));

        ConsolidationResponse result = service.diagnose();

        // a 와 b 는 조건이 같으므로 옮기는 비용도 같아야 한다.
        assertEquals(find(result, "a").relocationCostWatts(),
                find(result, "b").relocationCostWatts(), 1e-9,
                "앞 후보가 쌓은 예약이 남아 뒤 후보의 비용이 달라졌다");
    }

    // ── 요약 ──────────────────────────────────────────────────

    @Test
    void 반납_가능한_노드가_없으면_feasible_이_거짓이다() {
        given(List.of(node("only", 2, 10, 150)), List.of(workload(1L, "w1", 1)));

        ConsolidationResponse result = service.diagnose();

        assertFalse(result.feasible());
        assertEquals(0, result.summary().reclaimableNodes());
    }

    @Test
    void 한계를_응답에_명시한다() {
        given(List.of(node("idle", 1, 5, 150)), List.of());

        assertTrue(service.diagnose().limitation().contains("계산상 절감량"));
    }

    @Test
    void 총_전력이_0이면_절감_비율을_내지_않는다() {
        given(List.of(node("no-ipmi", 1, 5, 0), node("host", 30, 30, 0)), List.of());

        assertNull(service.diagnose().summary().savingPercent());
    }

    @Test
    void 요청당_메트릭_수집은_한_번이다() {
        // 예전에는 getNodeResources() 와 createContext() 가 각각 수집해 요청당 두 번이었다.
        // 두 스냅샷의 시점이 달라 1차 필터가 본 노드와 시뮬레이션 기준 노드가 어긋날 수 있었다.
        given(List.of(node("idle", 2, 10, 150), node("host", 40, 40, 250)),
                List.of(workload(1L, "w1", 1)));

        service.diagnose();

        verify(schedulingService, times(1)).createContext(any());
        verify(schedulingService, never()).getNodeResources();
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** 노드 목록과 "모든 노드에 공통으로 배치된" 워크로드를 세팅한다. */
    private void given(List<NodeResource> nodes, List<Workload> workloads) {
        SchedulingPolicy policy = new SchedulingPolicy("test", "test");
        when(schedulingService.resolvePolicy()).thenReturn(policy);
        when(schedulingService.getNodeResources()).thenReturn(nodes);
        // createContext 는 호출할 때마다 새 스냅샷을 줘야 한다 — 샌드박스가 원본을 건드리지
        // 않는지 확인하려면 복사본이어야 한다.
        when(schedulingService.createContext(any())).thenAnswer(inv ->
                PlacementContext.of(copy(nodes), policy));
        when(workloadRepository.findByAssignedNodeIdOrderByScheduledAtDesc(anyString()))
                .thenAnswer(inv -> workloads);
    }

    private List<NodeResource> copy(List<NodeResource> nodes) {
        List<NodeResource> out = new ArrayList<>();
        for (NodeResource n : nodes) {
            out.add(new NodeResource(n.getMdcId(), n.getNodeId(), n.getTotalCpu(), n.getTotalMemoryMb(),
                    n.getTotalGpu(), n.getCpuUsagePercent(), n.getMemoryUsagePercent(),
                    n.getDiskUsagePercent(), n.getPowerWatts(), n.getPowerCapacityWatts(),
                    n.getTemperatureCelsius(), n.getNetworkRxBps(), n.getNetworkTxBps()));
        }
        return out;
    }

    private Candidate find(ConsolidationResponse result, String nodeId) {
        return result.candidates().stream()
                .filter(c -> c.nodeId().equals(nodeId))
                .findFirst().orElseThrow();
    }

    private Workload workload(Long id, String name, int cpu) {
        Workload w = mock(Workload.class);
        when(w.getId()).thenReturn(id);
        when(w.getName()).thenReturn(name);
        when(w.getRequiredCpu()).thenReturn(cpu);
        when(w.getRequiredMemoryMb()).thenReturn(512);
        when(w.getRequiredGpu()).thenReturn(0);
        when(w.getStatus()).thenReturn(Workload.WorkloadStatus.RUNNING);
        // Mockito 는 Double 반환 메서드에 null 이 아니라 0.0 을 돌려준다. 그대로 두면
        // "0℃ 보다 뜨거운 노드 금지" · "0W 초과 금지" 가 되어 모든 노드가 제외된다.
        // 실제 엔티티는 이 두 값이 null 이므로 명시적으로 맞춰 준다.
        when(w.getMaxTemperatureCelsius()).thenReturn(null);
        when(w.getMaxPowerBudgetWatts()).thenReturn(null);
        return w;
    }

    private NodeResource node(String nodeId, double cpu, double mem, double power) {
        return node(nodeId, cpu, mem, power, 500);
    }

    private NodeResource node(String nodeId, double cpu, double mem, double power, double capacity) {
        return new NodeResource("mdc-01", nodeId, 8, 16384, 0,
                cpu, mem, 30, power, capacity, 45, 1_000_000, 500_000);
    }
}

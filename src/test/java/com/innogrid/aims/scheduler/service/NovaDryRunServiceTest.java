package com.innogrid.aims.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.NovaDryRunResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.inventory.NovaInventoryGateway;
import com.innogrid.aims.scheduler.inventory.NovaInventorySnapshot;
import com.innogrid.aims.scheduler.inventory.NovaInventorySnapshot.Node;
import com.innogrid.aims.scheduler.inventory.NovaInventorySnapshot.Server;
import com.innogrid.aims.scheduler.model.InventoryStatus;
import com.innogrid.aims.scheduler.model.NovaDryRunStatus;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Nova 인벤토리 모의 배치 — 실물 엔진(가중 점수)과 실물 제약 평가기로 스냅샷을 직접 넣어 확인한다(스프링 없음).
 *
 * <p>정책 저장소만 가짜다 — 비어 있으면 저장하지 않은 기본 정책(CPU 80% · 메모리 85%)을 고른다.
 * 서버 모양 골든은 배포 서버 판독을 순서를 지켜 익명화한 입력이다({@code scheduler/inventory/dry-run-server-shape.json}).</p>
 */
class NovaDryRunServiceTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-18T00:00:00Z");

    /** 서버 모양 입력의 모의 배치 뒤 쓰는 호스트 수와 호스트가 바뀐 VM 수(실물 엔진 결과를 고정). */
    private static final int GOLD_HOSTS_AFTER = 5;
    private static final int GOLD_HOST_CHANGED = 61;
    /** 서버 모양 입력의 놓은 뒤 CPU 사용률 · 상한 초과 · 메모리 사용률 최댓값(실물 엔진 결과를 고정). */
    private static final List<Double> GOLD_CPU_USAGE = List.of(83.33, 83.33, 81.25, 45.83, 81.25);
    private static final List<Boolean> GOLD_OVER_LIMIT = List.of(true, true, true, false, true);
    private static final double GOLD_MEMORY_MAX = 70.37;

    private SchedulingPolicyRepository policies;
    private WorkloadRepository workloads;
    private PlacementService placement;
    private NovaInventoryGateway gateway;
    private SchedulerProperties properties;
    private NovaDryRunService service;

    @BeforeEach
    void setUp() {
        policies = mock(SchedulingPolicyRepository.class);
        // 저장 경로로 바뀌어도 다른 테스트가 null 정책으로 먼저 죽지 않게 — 저장 없음은 saves_nothing 하나가 본다
        when(policies.save(any(SchedulingPolicy.class))).thenAnswer(call -> call.getArgument(0));
        workloads = mock(WorkloadRepository.class);
        placement = mock(PlacementService.class);
        gateway = mock(NovaInventoryGateway.class);
        properties = new SchedulerProperties();
        EngineRegistry engines = new EngineRegistry(List.of(new WeightedScoreScheduler()));
        SchedulingService scheduling = SchedulingServiceBuilder.aSchedulingService()
                .policies(policies).workloads(workloads).placement(placement).engines(engines).properties(properties).build();
        service = new NovaDryRunService(gateway, scheduling, engines, properties);
    }

    @DisplayName("빈 노드에서 요구 vCPU 큰 순으로 놓고 노드별 모의 값은 그 노드에 놓인 VM 의 합이다")
    @Test
    void places_largest_first_on_empty_nodes() {
        NovaDryRunResponse r = run(ok(List.of(node("h1", 16, 65_536), node("h2", 16, 65_536)),
                List.of(vm("s1", "h1", 2, 4_096), vm("s2", "h1", 8, 16_384), vm("s3", "h2", 4, 8_192))));

        assertEquals(NovaDryRunStatus.OK, r.status());
        assertEquals(List.of("s2", "s3", "s1"), r.decisions().stream().map(NovaDryRunResponse.Decision::serverId).toList());
        assertEquals(List.of("h1", "h2", "h2"), r.decisions().stream().map(NovaDryRunResponse.Decision::simulatedHost).toList(),
                "빈 노드는 앞 노드부터, 그 뒤로는 여유가 큰 노드");
        assertEquals(List.of(false, false, true), r.decisions().stream().map(NovaDryRunResponse.Decision::hostChanged).toList());
        assertEquals(1, r.hostChangedCount(), "s1 만 h1 에서 h2 로");
        assertEquals(2, r.hostsInUseAfter());
        assertTrue(r.decisions().stream().allMatch(NovaDryRunResponse.Decision::success));
        assertEquals(3, r.placedCount());
        assertEquals(0, r.failedCount());
        assertEquals(Map.of(), r.failedByReason());
        assertSums(r);
        NovaDryRunResponse.Node h1 = r.nodes().get(0);
        assertEquals("h1", h1.hypervisorHostname());
        assertEquals(2, h1.currentVmCount(), "지금 h1 위 대상 VM 은 s1 · s2");
        assertEquals(2, r.hostsInUseBefore());
        assertEquals("NOVA", r.source());
        assertEquals("LARGEST_FIRST", r.order());
        assertEquals("default", r.policyName());
        assertEquals("weighted", r.engine());
        assertEquals(80.0, r.cpuLimitPercent());
        assertEquals(85.0, r.memoryLimitPercent());
    }

    @DisplayName("놓인 VM 의 요구량을 예약으로 쌓아 물리 vCPU 가 모자라면 INSUFFICIENT_RESOURCE 로 못 놓는다")
    @Test
    void reserves_each_placement_so_a_full_node_turns_others_away() {
        NovaDryRunResponse r = run(ok(List.of(node("h1", 10, 100_000)),
                List.of(vm("a", "h1", 4, 1_024), vm("b", "h1", 4, 1_024), vm("c", "h1", 4, 1_024))));

        assertEquals(2, r.placedCount());
        assertEquals(1, r.failedCount());
        assertEquals(Map.of("INSUFFICIENT_RESOURCE", 1), r.failedByReason());
        assertEquals(8, r.nodes().get(0).simulatedCpu());
        NovaDryRunResponse.Decision last = r.decisions().get(2);
        assertFalse(last.success());
        assertNull(last.simulatedHost());
        assertFalse(last.hostChanged());
        assertEquals("{INSUFFICIENT_RESOURCE:1}", last.exclusionCodes().replace("\"", ""));
        assertSums(r);
        NovaDryRunResponse.Node h1 = r.nodes().get(0);
        assertEquals(80.0, h1.simulatedCpuUsagePercent(), "8/10");
        assertFalse(h1.overLimit(), "80% 는 상한 80% 를 넘지 않는다(엔진 C2 와 같은 엄격한 비교)");
        assertEquals(0, r.overLimitNodeCount());
    }

    @DisplayName("코어가 남아도 놓기 전 사용률이 정책 상한을 넘은 노드에는 놓지 않는다(CPU_USAGE_EXCEEDED)")
    @Test
    void policy_cpu_limit_turns_away_even_with_free_cores() {
        NovaDryRunResponse r = run(ok(List.of(node("h1", 10, 100_000)),
                List.of(vm("big", "h1", 9, 1_024), vm("small", "h1", 1, 1_024))));

        assertEquals(1, r.placedCount());
        assertEquals(Map.of("CPU_USAGE_EXCEEDED", 1), r.failedByReason());
        assertEquals(9, r.nodes().get(0).simulatedCpu(), "물리 vCPU 는 1 남지만 놓기 전 90% > 80%");
        assertEquals(90.0, r.nodes().get(0).simulatedCpuUsagePercent());
        assertTrue(r.nodes().get(0).overLimit());
        assertEquals(1, r.overLimitNodeCount());
    }

    @DisplayName("놓기 전 80% 인 노드에도 놓이므로(80 > 80 거짓) 놓은 뒤 사용률은 상한을 넘을 수 있고 overLimit 이 true 다")
    @Test
    void placement_at_the_limit_can_end_over_the_limit() {
        NovaDryRunResponse r = run(ok(List.of(node("h1", 10, 100_000)), List.of(vm("a", "h1", 8, 1_024), vm("b", "h1", 1, 1_024))));

        assertEquals(2, r.placedCount(), "두 번째 VM 은 놓기 전 80% 라 놓인다");
        assertEquals(90.0, r.nodes().get(0).simulatedCpuUsagePercent());
        assertTrue(r.nodes().get(0).overLimit());
        assertEquals(1, r.overLimitNodeCount());
    }

    @DisplayName("메모리 사용률이 상한과 같으면 넘지 않고, 메모리만 넘어도 overLimit 이며 노드 수에 든다")
    @Test
    void memory_limit_is_compared_like_cpu() {
        NovaDryRunResponse atLimit = run(ok(List.of(node("h1", 16, 100_000)), List.of(vm("a", "h1", 1, 85_000))));
        assertEquals(85.0, atLimit.nodes().get(0).simulatedMemoryUsagePercent());
        assertFalse(atLimit.nodes().get(0).overLimit(), "85% 는 상한 85% 를 넘지 않는다");
        assertEquals(0, atLimit.overLimitNodeCount());

        NovaDryRunResponse memoryOnly = run(ok(List.of(node("h1", 16, 10_000)), List.of(vm("a", "h1", 1, 9_000))));
        NovaDryRunResponse.Node h1 = memoryOnly.nodes().get(0);
        assertEquals(6.25, h1.simulatedCpuUsagePercent(), "CPU 는 상한 아래");
        assertEquals(90.0, h1.simulatedMemoryUsagePercent());
        assertTrue(h1.overLimit());
        assertEquals(1, memoryOnly.overLimitNodeCount(), "메모리만 넘은 노드도 센다");
    }

    @DisplayName("상한 비교는 반올림 전 값으로 한다 — 85.004% 는 85.0 으로 보이지만 상한 85% 를 넘는다")
    @Test
    void over_limit_is_judged_before_rounding() {
        NovaDryRunResponse r = run(ok(List.of(node("h1", 16, 100_000)), List.of(vm("a", "h1", 1, 85_004))));

        assertEquals(85.0, r.nodes().get(0).simulatedMemoryUsagePercent(), "표시는 둘째 자리 반올림");
        assertTrue(r.nodes().get(0).overLimit(), "판정은 85.004 > 85");
    }

    @DisplayName("총량이 0 인 노드는 사용률이 null 이고 overLimit 은 false 다 · 노드 키는 기존 필드 뒤에 붙는다")
    @Test
    void zero_total_node_has_no_usage_and_keys_follow_existing_fields() {
        NovaDryRunResponse r = run(ok(List.of(node("h0", 0, 0), node("h1", 16, 65_536)), List.of(vm("a", "h1", 2, 2_048))));

        NovaDryRunResponse.Node h0 = r.nodes().get(0);
        assertEquals("h0", h0.hypervisorHostname());
        assertNull(h0.simulatedCpuUsagePercent());
        assertNull(h0.simulatedMemoryUsagePercent());
        assertFalse(h0.overLimit());
        JsonNode json = new ObjectMapper().valueToTree(h0);
        List<String> keys = new ArrayList<>();
        json.propertyNames().forEach(keys::add);
        assertEquals(List.of("hypervisorHostname", "totalCpu", "totalMemoryMb", "currentAllocatedCpu", "currentAllocatedMemoryMb",
                "currentVmCount", "simulatedCpu", "simulatedMemoryMb", "simulatedVmCount", "simulatedCpuUsagePercent",
                "simulatedMemoryUsagePercent", "overLimit"), keys);
        assertTrue(json.get("simulatedCpuUsagePercent").isNull(), "모르는 사용률은 0 이 아니라 null");
    }

    @DisplayName("변환 가능하고 지금 호스트가 쓸 수 있는 노드인 VM 만 다시 놓고 나머지는 사유와 함께 대상 밖에 둔다")
    @Test
    void only_vms_on_usable_hosts_are_placed_again() {
        List<Server> servers = List.of(vm("a", "h1", 2, 2_048), vm("b", "h2", 2, 2_048),
                new Server("c", "vm-c", "SHELVED_OFFLOADED", null, "f", 2, 2_048, 10, true, null),
                new Server("d", "vm-d", "ACTIVE", "h1", "f9", null, null, null, false, "flavor 조회 실패: f9"),
                vm("e", "h9", 2, 2_048));
        NovaDryRunResponse r = run(ok(List.of(node("h1", 16, 65_536), disabled("h2", 64, 131_072)), servers));

        assertEquals(NovaDryRunStatus.OK, r.status(), "flavor 를 못 읽은 VM 이 있어도 다른 VM 이 남으면 OK");
        assertEquals(1, r.nodeCount());
        assertEquals(1, r.vmCount());
        assertEquals(4, r.excludedCount());
        assertEquals(List.of("a"), r.decisions().stream().map(NovaDryRunResponse.Decision::serverId).toList());
        assertEquals(List.of(
                "b 지금 호스트를 쓸 수 없음(status disabled)",
                "c 지금 호스트 없음(보관 등)",
                "d 변환 불가: flavor 조회 실패: f9",
                "e 지금 호스트를 쓸 수 없음(하이퍼바이저 목록에 없음)"),
                r.excluded().stream().map(x -> x.serverId() + " " + x.reason()).toList());
        assertEquals(1, r.hostsInUseBefore(), "쓸 수 없는 호스트는 세지 않는다");
    }

    @DisplayName("VM 목록 질의만 실패해도 VM 0대가 아니라 INVENTORY_FAILED 다")
    @Test
    void vm_list_failure_is_inventory_failed() {
        NovaInventorySnapshot snapshot = new NovaInventorySnapshot(InventoryStatus.OK, T0, 5, List.of("servers"),
                List.of(node("h1", 16, 65_536)), false, List.of());
        NovaDryRunResponse r = run(snapshot);

        assertEquals(NovaDryRunStatus.INVENTORY_FAILED, r.status());
        assertEquals(InventoryStatus.OK, r.inventoryStatus());
        assertEquals(List.of("servers"), r.unavailable());
        assertEquals(1, r.nodeCount());
        assertNull(r.vmCount());
        assertNull(r.excludedCount());
        assertNull(r.placedCount());
        assertNull(r.overLimitNodeCount());
        assertTrue(r.decisions().isEmpty());
    }

    @DisplayName("쓸 수 있는 호스트 위 VM 의 flavor 를 못 읽어 대상이 비면 VM 0대가 아니라 INVENTORY_FAILED 이고 대상 밖 사유는 남는다")
    @Test
    void flavor_unread_on_usable_host_is_inventory_failed() {
        NovaDryRunResponse r = run(flavorTrouble(List.of(node("h1", 16, 65_536)), List.of(flavorFailed("d", "h1"))));

        assertEquals(NovaDryRunStatus.INVENTORY_FAILED, r.status());
        assertEquals(InventoryStatus.OK, r.inventoryStatus(), "목록은 읽었다");
        assertEquals(List.of("flavors"), r.unavailable());
        assertEquals(1, r.nodeCount());
        assertEquals(0, r.vmCount(), "VM 목록 실패(null)와 가르는 값");
        assertEquals(1, r.excludedCount());
        assertEquals("변환 불가: flavor 조회 실패: f9", r.excluded().get(0).reason());
        assertNull(r.placedCount());
        assertNull(r.overLimitNodeCount());
    }

    @DisplayName("flavor 를 못 읽은 VM 이 쓸 수 없는 호스트에만 있거나 Nova 가 flavor 를 주지 않은 것이면 NO_VMS 다")
    @Test
    void flavor_trouble_elsewhere_or_missing_flavor_stays_no_vms() {
        NovaDryRunResponse elsewhere = run(flavorTrouble(List.of(node("h1", 16, 65_536), disabled("h2", 64, 131_072)),
                List.of(flavorFailed("d", "h2"))));
        assertEquals(NovaDryRunStatus.NO_VMS, elsewhere.status(), "쓸 수 없는 호스트 위 VM 은 어차피 대상 밖");

        NovaDryRunResponse missing = run(ok(List.of(node("h1", 16, 65_536)),
                List.of(new Server("m", "vm-m", "ACTIVE", "h1", null, null, null, null, false, "flavor 없음"))));
        assertEquals(NovaDryRunStatus.NO_VMS, missing.status(), "flavor id 가 없으면 조회 실패가 아니다");
        assertEquals(1, missing.excludedCount());
    }

    @DisplayName("인벤토리 조회가 실패하면 조회 상태를 그대로 싣고 노드 수는 모른다")
    @Test
    void inventory_failure_keeps_inventory_status() {
        NovaDryRunResponse r = run(new NovaInventorySnapshot(InventoryStatus.CONNECTION_FAILED, T0, 3, List.of(), List.of(), false, List.of()));

        assertEquals(NovaDryRunStatus.INVENTORY_FAILED, r.status());
        assertEquals(InventoryStatus.CONNECTION_FAILED, r.inventoryStatus());
        assertNull(r.nodeCount());
        assertNull(r.policyName());
    }

    @DisplayName("쓸 수 있는 노드가 없으면 NO_NODES, 다시 놓을 VM 이 없으면 NO_VMS")
    @Test
    void no_usable_nodes_or_no_targets() {
        NovaDryRunResponse empty = run(new NovaInventorySnapshot(InventoryStatus.EMPTY, T0, 5, List.of(), List.of(), true, List.of()));
        assertEquals(NovaDryRunStatus.NO_NODES, empty.status());
        assertEquals(InventoryStatus.EMPTY, empty.inventoryStatus());
        assertEquals(0, empty.nodeCount());
        assertNull(empty.overLimitNodeCount(), "모의 배치를 하지 않은 상태는 null");

        NovaDryRunResponse allDisabled = run(ok(List.of(disabled("h2", 64, 131_072)), List.of(vm("b", "h2", 2, 2_048))));
        assertEquals(NovaDryRunStatus.NO_NODES, allDisabled.status());
        assertEquals(1, allDisabled.excludedCount());

        NovaDryRunResponse noVms = run(ok(List.of(node("h1", 16, 65_536)), List.of()));
        assertEquals(NovaDryRunStatus.NO_VMS, noVms.status());
        assertEquals(1, noVms.nodeCount());
        assertEquals(0, noVms.vmCount());
        assertNull(noVms.placedCount());
        assertNull(noVms.overLimitNodeCount());
        assertNull(allDisabled.overLimitNodeCount());
    }

    @DisplayName("플래그가 꺼져 있으면 Nova 를 부르지 않고 DISABLED 다")
    @Test
    void disabled_flag_skips_inventory() {
        NovaDryRunResponse r = service.dryRun();

        assertEquals(NovaDryRunStatus.DISABLED, r.status());
        assertNull(r.inventoryStatus());
        assertNull(r.nodeCount());
        assertNull(r.excludedCount());
        assertTrue(r.unavailable().isEmpty());
        assertNull(r.overLimitNodeCount());
        verifyNoInteractions(gateway);
    }

    @DisplayName("플래그는 호출마다 읽는다 — 켜면 조회 결과로 모의 배치를 하고 끄면 다시 부르지 않는다")
    @Test
    void flag_is_read_on_every_call() {
        when(gateway.read()).thenReturn(ok(List.of(node("h1", 16, 65_536)), List.of(vm("a", "h1", 2, 2_048))));

        properties.getInventory().setSimulationEnabled(true);
        assertEquals(NovaDryRunStatus.OK, service.dryRun().status());
        properties.getInventory().setSimulationEnabled(false);
        assertEquals(NovaDryRunStatus.DISABLED, service.dryRun().status());
        verify(gateway, times(1)).read();
    }

    @DisplayName("정책은 VM 배치와 같은 규칙으로 고른다 — VM 종류 활성 정책이 있으면 그 정책의 상한을 쓴다")
    @Test
    void uses_the_vm_policy_like_vm_placement() {
        SchedulingPolicy vmPolicy = new SchedulingPolicy("vm-policy", "VM 전용");
        vmPolicy.setResourceType(Workload.ResourceType.VM);
        vmPolicy.setActive(true);
        vmPolicy.setMaxCpuUsagePercent(50);
        when(policies.findByActiveTrueAndResourceTypeOrderByNameAsc(Workload.ResourceType.VM)).thenReturn(List.of(vmPolicy));

        NovaDryRunResponse r = run(ok(List.of(node("h1", 10, 100_000)), List.of(vm("a", "h1", 6, 1_024), vm("b", "h1", 1, 1_024))));

        assertEquals("vm-policy", r.policyName());
        assertEquals(50.0, r.cpuLimitPercent());
        assertEquals(Map.of("CPU_USAGE_EXCEEDED", 1), r.failedByReason(), "놓기 전 60% > 50%");
        assertEquals(60.0, r.nodes().get(0).simulatedCpuUsagePercent());
        assertTrue(r.nodes().get(0).overLimit(), "VM 정책의 상한(50%)과 비교한다");
    }

    @DisplayName("워크로드 사본은 저장하지 않은 VM 이고 디스크 요구가 0 이다")
    @Test
    void workload_copy_is_unsaved_vm_without_disk_demand() {
        Workload w = NovaDryRunService.toWorkload(vm("a", "h1", 2, 2_048));

        assertNull(w.getId());
        assertEquals(Workload.ResourceType.VM, w.getResourceType());
        assertEquals(0, w.getRequiredDiskGb(), "flavor 디스크(10GB)는 넣지 않는다");
        assertEquals(2, w.getRequiredCpu());
    }

    @DisplayName("배포 서버 모양 입력 — 쓸 수 있는 호스트 5대 위 VM 72대를 모두 놓고 disabled 호스트 위 1대는 대상 밖이며 두 번 돌려도 같다")
    @Test
    void server_shaped_inventory() throws Exception {
        NovaInventorySnapshot snapshot = serverShaped();
        NovaDryRunResponse r = run(snapshot);
        NovaDryRunResponse again = run(snapshot);

        assertEquals(NovaDryRunStatus.OK, r.status());
        assertEquals(5, r.nodeCount());
        assertEquals(72, r.vmCount());
        assertEquals(1, r.excludedCount());
        assertEquals("지금 호스트를 쓸 수 없음(status disabled)", r.excluded().get(0).reason());
        assertEquals(72, r.placedCount());
        assertEquals(0, r.failedCount());
        assertEquals(5, r.hostsInUseBefore());
        assertEquals(GOLD_HOSTS_AFTER, r.hostsInUseAfter());
        assertEquals(GOLD_HOST_CHANGED, r.hostChangedCount());
        assertEquals(GOLD_CPU_USAGE, r.nodes().stream().map(NovaDryRunResponse.Node::simulatedCpuUsagePercent).toList(),
                "놓은 뒤 CPU 사용률(노드 순서 = 스냅샷 순서)");
        assertEquals(GOLD_OVER_LIMIT, r.nodes().stream().map(NovaDryRunResponse.Node::overLimit).toList());
        assertEquals(4, r.overLimitNodeCount(), "다 놓였지만 5대 가운데 4대가 CPU 상한 80% 를 넘는다");
        assertEquals(GOLD_MEMORY_MAX, r.nodes().stream().mapToDouble(NovaDryRunResponse.Node::simulatedMemoryUsagePercent).max().orElseThrow());
        List<Integer> cpus = r.decisions().stream().map(NovaDryRunResponse.Decision::requiredCpu).toList();
        for (int i = 1; i < cpus.size(); i++) assertTrue(cpus.get(i - 1) >= cpus.get(i), "요구 vCPU 큰 순");
        assertSums(r);
        assertEquals(r.decisions(), again.decisions(), "같은 입력이면 같은 결정");
        assertEquals(r.nodes(), again.nodes());
    }

    @DisplayName("저장하지 않는다 — 워크로드 · 배치 쪽을 부르지 않고 정책도 저장하지 않는다")
    @Test
    void saves_nothing() throws Exception {
        NovaDryRunResponse r = run(serverShaped());

        assertEquals(72, r.placedCount());
        verifyNoInteractions(workloads, placement);
        verify(policies, never()).save(any());
    }

    @DisplayName("못 놓인 VM 하나의 후보 노드 둘이 같은 코드로 빠지면 그 코드는 한 번만 센다")
    @Test
    void same_code_on_two_nodes_counts_once() {
        NovaDryRunResponse r = run(ok(List.of(node("h1", 4, 65_536), node("h2", 4, 65_536)), List.of(vm("big", "h1", 8, 1_024))));

        assertEquals(1, r.failedCount());
        assertEquals(Map.of("INSUFFICIENT_RESOURCE", 1), r.failedByReason(), "노드 둘이 같은 코드여도 VM 하나");
    }

    @DisplayName("평가 중 오류가 난 VM 은 못 놓인 것으로 남고 사유 코드 집계에는 들지 않으며 다른 VM 은 계속 놓는다")
    @Test
    void evaluation_error_is_failed_but_not_counted_by_code() {
        WeightedScoreScheduler weighted = new WeightedScoreScheduler();
        SchedulingEngine boom = new SchedulingEngine() {
            @Override
            public SchedulingResult schedule(Workload workload, PlacementContext context) {
                if ("vm-boom".equals(workload.getName())) throw new IllegalStateException("boom");
                return weighted.schedule(workload, context);
            }

            @Override
            public String getName() {
                return weighted.getName();
            }

            @Override
            public String getId() {
                return weighted.getId();
            }
        };

        NovaDryRunResponse r = serviceWith(boom).simulate(ok(List.of(node("h1", 16, 65_536)),
                List.of(vm("boom", "h1", 4, 1_024), vm("ok", "h1", 2, 1_024))), T0, System.nanoTime());

        NovaDryRunResponse.Decision failed = r.decisions().stream().filter(d -> d.serverId().equals("boom")).findFirst().orElseThrow();
        assertFalse(failed.success());
        assertEquals("평가 중 오류: boom", failed.reason());
        assertNull(failed.exclusionCodes());
        assertEquals(1, r.failedCount());
        assertEquals(Map.of(), r.failedByReason(), "코드가 없어 세지 않는다");
        assertEquals(1, r.placedCount(), "다른 VM 은 놓인다");
    }

    @DisplayName("정책의 기본 엔진으로 실제 평가한다 — 응답의 engine 과 평가에 쓰인 엔진이 같다")
    @Test
    void evaluates_with_the_policy_engine() {
        CountingEngine temperature = new CountingEngine(new TemperatureAwareScheduler());
        NovaDryRunService withEngines = serviceWith(new WeightedScoreScheduler(), temperature, new PowerAwareScheduler());
        useVmPolicyWithEngine("temperature");

        NovaDryRunResponse r = withEngines.simulate(ok(List.of(node("h1", 16, 65_536), node("h2", 16, 65_536)),
                List.of(vm("a", "h1", 2, 2_048), vm("b", "h2", 4, 4_096))), T0, System.nanoTime());

        assertEquals("temperature", r.engine());
        assertEquals(2, temperature.calls, "두 VM 모두 정책 엔진으로 평가");
    }

    @DisplayName("서버 모양 입력을 엔진 셋으로 놓으면 노드별 합과 놓인 수는 같지만 VM 별 결정은 엔진에 따라 다를 수 있다")
    @Test
    void engines_agree_on_node_totals_but_not_on_every_vm() throws Exception {
        NovaDryRunService withEngines = serviceWith(new WeightedScoreScheduler(), new TemperatureAwareScheduler(), new PowerAwareScheduler());
        NovaInventorySnapshot snapshot = serverShaped();
        Map<String, NovaDryRunResponse> byEngine = new LinkedHashMap<>();
        for (String engine : List.of("weighted", "temperature", "power")) {
            useVmPolicyWithEngine(engine);
            byEngine.put(engine, withEngines.simulate(snapshot, T0, System.nanoTime()));
        }

        NovaDryRunResponse weighted = byEngine.get("weighted");
        for (NovaDryRunResponse r : byEngine.values()) {
            assertEquals(72, r.placedCount(), r.engine());
            assertEquals(nodeTotals(weighted), nodeTotals(r), r.engine() + " 노드별 합 · 놓인 수");
        }
        assertEquals(hosts(weighted), hosts(byEngine.get("power")), "계측이 없으면 power 는 weighted 와 같은 노드를 고른다");
        List<String> w = hosts(weighted), t = hosts(byEngine.get("temperature"));
        long differ = java.util.stream.IntStream.range(0, w.size()).filter(i -> !w.get(i).equals(t.get(i))).count();
        assertEquals(GOLD_TEMPERATURE_DIFFERS, differ, "temperature 는 스코어 식과 반올림 뒤 동점 여부가 달라 weighted 와 다른 노드를 고르는 VM 이 있다");
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /** 서버 모양 입력에서 temperature 엔진이 weighted와 다른 노드를 고르는 VM 수(실물 엔진 결과를 고정). */
    private static final long GOLD_TEMPERATURE_DIFFERS = 2;

    /** 엔진을 여럿 등록한 서비스 — 서비스와 SchedulingService가 같은 레지스트리를 쓴다(없는 id는 조용히 기본 엔진이 되므로). */
    private NovaDryRunService serviceWith(SchedulingEngine... engines) {
        EngineRegistry registry = new EngineRegistry(List.of(engines));
        SchedulingService scheduling = SchedulingServiceBuilder.aSchedulingService()
                .policies(policies).engines(registry).properties(properties).build();
        return new NovaDryRunService(gateway, scheduling, registry, properties);
    }

    /** 기본 엔진만 정한 VM 종류 활성 정책(상한 · 가중치는 기본값). */
    private void useVmPolicyWithEngine(String engine) {
        SchedulingPolicy policy = new SchedulingPolicy("vm-" + engine, "VM 전용");
        policy.setResourceType(Workload.ResourceType.VM);
        policy.setActive(true);
        policy.setDefaultEngine(engine);
        when(policies.findByActiveTrueAndResourceTypeOrderByNameAsc(Workload.ResourceType.VM)).thenReturn(List.of(policy));
    }

    private static List<String> hosts(NovaDryRunResponse r) {
        return r.decisions().stream().map(NovaDryRunResponse.Decision::simulatedHost).toList();
    }

    private static List<String> nodeTotals(NovaDryRunResponse r) {
        return r.nodes().stream().map(n -> n.hypervisorHostname() + ":" + n.simulatedCpu() + "/" + n.simulatedMemoryMb() + "/" + n.simulatedVmCount())
                .toList();
    }

    /** 평가 호출 수를 세는 엔진 — 응답의 engine 이름만이 아니라 실제로 평가에 쓰였는지 본다. */
    private static final class CountingEngine implements SchedulingEngine {
        private final SchedulingEngine delegate;
        private int calls;

        CountingEngine(SchedulingEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public SchedulingResult schedule(Workload workload, PlacementContext context) {
            calls++;
            return delegate.schedule(workload, context);
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getId() {
            return delegate.getId();
        }
    }

    private NovaDryRunResponse run(NovaInventorySnapshot snapshot) {
        return service.simulate(snapshot, T0, System.nanoTime());
    }

    /** 노드별 모의 값이 그 노드에 놓인 결정의 합이고 물리 총량을 넘지 않는다. */
    private static void assertSums(NovaDryRunResponse r) {
        for (NovaDryRunResponse.Node n : r.nodes()) {
            List<NovaDryRunResponse.Decision> here = r.decisions().stream()
                    .filter(d -> n.hypervisorHostname().equals(d.simulatedHost())).toList();
            assertEquals(here.stream().mapToInt(NovaDryRunResponse.Decision::requiredCpu).sum(), n.simulatedCpu(), n.hypervisorHostname());
            assertEquals(here.stream().mapToLong(NovaDryRunResponse.Decision::requiredMemoryMb).sum(), n.simulatedMemoryMb(), n.hypervisorHostname());
            assertEquals(here.size(), n.simulatedVmCount(), n.hypervisorHostname());
            assertTrue(n.simulatedCpu() <= n.totalCpu() && n.simulatedMemoryMb() <= n.totalMemoryMb(), n.hypervisorHostname());
        }
        assertEquals(r.vmCount(), r.placedCount() + r.failedCount());
    }

    private static NovaInventorySnapshot ok(List<Node> nodes, List<Server> servers) {
        return new NovaInventorySnapshot(InventoryStatus.OK, T0, 5, List.of(), nodes, true, servers);
    }

    /** 게이트웨이가 flavor 조회에 실패했을 때의 모양 — unavailable에 flavors가 있다. */
    private static NovaInventorySnapshot flavorTrouble(List<Node> nodes, List<Server> servers) {
        return new NovaInventorySnapshot(InventoryStatus.OK, T0, 5, List.of("flavors"), nodes, true, servers);
    }

    /** flavor 조회에 실패한 VM — flavor id는 있고 요구 자원이 없으며 변환 불가(게이트웨이 모양). */
    private static Server flavorFailed(String id, String host) {
        return new Server(id, "vm-" + id, "ACTIVE", host, "f9", null, null, null, false, "flavor 조회 실패: f9");
    }

    private static Node node(String host, int cpu, long memoryMb) {
        return new Node(host, "nova", "up", "enabled", cpu, 0, memoryMb, 0L, null, null, 0, true, null);
    }

    private static Node disabled(String host, int cpu, long memoryMb) {
        return new Node(host, "nova", "up", "disabled", cpu, cpu, memoryMb, memoryMb, null, null, 1, false, "status disabled");
    }

    private static Server vm(String id, String host, int cpu, int memoryMb) {
        return new Server(id, "vm-" + id, "ACTIVE", host, "f", cpu, memoryMb, 10, true, null);
    }

    private static NovaInventorySnapshot serverShaped() throws Exception {
        JsonNode doc;
        try (InputStream in = NovaDryRunServiceTest.class.getResourceAsStream("/scheduler/inventory/dry-run-server-shape.json")) {
            doc = new ObjectMapper().readTree(in);
        }
        List<Node> nodes = new ArrayList<>();
        for (JsonNode n : doc.get("nodes")) {
            nodes.add(new Node(text(n, "hypervisorHostname"), text(n, "availabilityZone"), text(n, "state"), text(n, "status"),
                    n.get("totalCpu").asInt(), n.get("allocatedCpu").asInt(), n.get("totalMemoryMb").asLong(), n.get("allocatedMemoryMb").asLong(),
                    n.get("diskCapacityGb").asLong(), n.get("allocatedDiskGb").asLong(), n.get("runningVms").asInt(),
                    n.get("usable").asBoolean(), text(n, "skipReason")));
        }
        List<Server> servers = new ArrayList<>();
        for (JsonNode s : doc.get("servers")) {
            servers.add(new Server(text(s, "serverId"), text(s, "name"), text(s, "status"), text(s, "hypervisorHostname"), text(s, "flavorId"),
                    s.get("requiredCpu").asInt(), s.get("requiredMemoryMb").asInt(), s.get("requiredDiskGb").asInt(),
                    s.get("convertible").asBoolean(), text(s, "skipReason")));
        }
        return ok(nodes, servers);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}

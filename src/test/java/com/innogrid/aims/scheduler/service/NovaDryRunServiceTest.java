package com.innogrid.aims.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.NovaDryRunResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.inventory.NovaInventoryGateway;
import com.innogrid.aims.scheduler.inventory.NovaInventorySnapshot;
import com.innogrid.aims.scheduler.inventory.NovaInventorySnapshot.Node;
import com.innogrid.aims.scheduler.inventory.NovaInventorySnapshot.Server;
import com.innogrid.aims.scheduler.model.InventoryStatus;
import com.innogrid.aims.scheduler.model.NovaDryRunStatus;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;

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

    private SchedulingPolicyRepository policies;
    private NovaInventoryGateway gateway;
    private SchedulerProperties properties;
    private NovaDryRunService service;

    @BeforeEach
    void setUp() {
        policies = mock(SchedulingPolicyRepository.class);
        gateway = mock(NovaInventoryGateway.class);
        properties = new SchedulerProperties();
        EngineRegistry engines = new EngineRegistry(List.of(new WeightedScoreScheduler()));
        SchedulingService scheduling = SchedulingServiceBuilder.aSchedulingService()
                .policies(policies).engines(engines).properties(properties).build();
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
    }

    @DisplayName("코어가 남아도 놓기 전 사용률이 정책 상한을 넘은 노드에는 놓지 않는다(CPU_USAGE_EXCEEDED)")
    @Test
    void policy_cpu_limit_turns_away_even_with_free_cores() {
        NovaDryRunResponse r = run(ok(List.of(node("h1", 10, 100_000)),
                List.of(vm("big", "h1", 9, 1_024), vm("small", "h1", 1, 1_024))));

        assertEquals(1, r.placedCount());
        assertEquals(Map.of("CPU_USAGE_EXCEEDED", 1), r.failedByReason());
        assertEquals(9, r.nodes().get(0).simulatedCpu(), "물리 vCPU 는 1 남지만 놓기 전 90% > 80%");
    }

    @DisplayName("변환 가능하고 지금 호스트가 쓸 수 있는 노드인 VM 만 다시 놓고 나머지는 사유와 함께 대상 밖에 둔다")
    @Test
    void only_vms_on_usable_hosts_are_placed_again() {
        List<Server> servers = List.of(vm("a", "h1", 2, 2_048), vm("b", "h2", 2, 2_048),
                new Server("c", "vm-c", "SHELVED_OFFLOADED", null, "f", 2, 2_048, 10, true, null),
                new Server("d", "vm-d", "ACTIVE", "h1", "f9", null, null, null, false, "flavor 조회 실패: f9"),
                vm("e", "h9", 2, 2_048));
        NovaDryRunResponse r = run(ok(List.of(node("h1", 16, 65_536), disabled("h2", 64, 131_072)), servers));

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
        assertTrue(r.decisions().isEmpty());
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

        NovaDryRunResponse allDisabled = run(ok(List.of(disabled("h2", 64, 131_072)), List.of(vm("b", "h2", 2, 2_048))));
        assertEquals(NovaDryRunStatus.NO_NODES, allDisabled.status());
        assertEquals(1, allDisabled.excludedCount());

        NovaDryRunResponse noVms = run(ok(List.of(node("h1", 16, 65_536)), List.of()));
        assertEquals(NovaDryRunStatus.NO_VMS, noVms.status());
        assertEquals(1, noVms.nodeCount());
        assertEquals(0, noVms.vmCount());
        assertNull(noVms.placedCount());
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
        List<Integer> cpus = r.decisions().stream().map(NovaDryRunResponse.Decision::requiredCpu).toList();
        for (int i = 1; i < cpus.size(); i++) assertTrue(cpus.get(i - 1) >= cpus.get(i), "요구 vCPU 큰 순");
        assertSums(r);
        assertEquals(r.decisions(), again.decisions(), "같은 입력이면 같은 결정");
        assertEquals(r.nodes(), again.nodes());
    }

    // ── 도우미 ──────────────────────────────────────────────────

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

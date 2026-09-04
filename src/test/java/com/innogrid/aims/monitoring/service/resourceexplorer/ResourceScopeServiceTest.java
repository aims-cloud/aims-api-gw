package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.entity.RoleAsset;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleAssetRepository;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedVm;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MonitoringCapabilityResolver;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MonitoringCapabilityResolver.Snapshot;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.Sebu1VirtualResourceProvider;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.Sebu1VirtualResourceProvider.VirtualResource;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 역할 스코프 자원 목록 산출 테스트 — 물리 호스트 실행 상태 판정 규칙, 1세부 가상자원·Tumblebug VM 결합,
 * 역할 중복 제거. 원장·Thanos·Tumblebug 스냅샷은 대역.
 */
class ResourceScopeServiceTest {

    AuthService authService = mock(AuthService.class);
    RoleAssetRepository roleAssetRepository = mock(RoleAssetRepository.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    MonitoringTargetRepository targetRepository = mock(MonitoringTargetRepository.class);
    MonitoringCapabilityResolver resolver = mock(MonitoringCapabilityResolver.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);
    Sebu1VirtualResourceProvider vmProvider = mock(Sebu1VirtualResourceProvider.class);

    ResourceScopeService service;

    Asset active;        // node-01: node/ipmi 등록, up=1, 전원 켜짐 → RUNNING
    Asset powerOff;      // node-02: ipmi 전원 꺼짐 → STOP
    Asset down;          // node-03: node_exporter 등록, up=0 → ERROR
    Asset noExporter;    // node-04: exporter 미등록 → PENDING
    Asset maintenance;   // node-05: 자산 상태 MAINTENANCE → PENDING
    Asset retired;       // node-06: 자산 상태 DECOMMISSIONED → STOP

    @BeforeEach
    void setUp() {
        Role admin = new Role("ADMIN", "관리자");
        Role user = new Role("USER", "사용자");
        active = asset(1L, "mdc-01", "node-01", Asset.AssetStatus.ACTIVE);
        powerOff = asset(2L, "mdc-01", "node-02", Asset.AssetStatus.ACTIVE);
        down = asset(3L, "mdc-02", "node-03", Asset.AssetStatus.ACTIVE);
        noExporter = asset(4L, "mdc-02", "node-04", Asset.AssetStatus.ACTIVE);
        maintenance = asset(5L, "mdc-02", "node-05", Asset.AssetStatus.MAINTENANCE);
        retired = asset(6L, "mdc-02", "node-06", Asset.AssetStatus.DECOMMISSIONED);
        Asset sanSwitch = asset(7L, "mdc-01", "san-01", Asset.AssetStatus.ACTIVE);
        ReflectionTestUtils.setField(sanSwitch, "type", Asset.AssetType.SAN_SWITCH);

        Map<String, List<RoleAsset>> assetMappings = Map.of(
                "ADMIN", List.of(new RoleAsset(admin, active), new RoleAsset(admin, powerOff), new RoleAsset(admin, down),
                        new RoleAsset(admin, noExporter), new RoleAsset(admin, maintenance), new RoleAsset(admin, retired),
                        new RoleAsset(admin, sanSwitch)),
                "USER", List.of(new RoleAsset(user, active)));
        when(roleAssetRepository.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> names = inv.getArgument(0);
            return names.stream().flatMap(n -> assetMappings.getOrDefault(n, List.of()).stream()).toList();
        });

        List<MonitoringTarget> targets = List.of(
                target(active, "10.0.0.1", 9100, ExporterType.NODE_EXPORTER),
                target(active, "10.0.0.1", 9290, ExporterType.IPMI_EXPORTER),
                target(powerOff, "10.0.0.2", 9100, ExporterType.NODE_EXPORTER),
                target(powerOff, "10.0.0.2", 9290, ExporterType.IPMI_EXPORTER),
                target(down, "10.0.0.3", 9100, ExporterType.NODE_EXPORTER));
        when(targetRepository.findByAsset_IdIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return targets.stream().filter(t -> ids.contains(t.getAsset().getId())).toList();
        });

        when(resolver.snapshot()).thenReturn(new Snapshot(Instant.now(),
                Map.of("10.0.0.1:9100", true, "10.0.0.1:9290", true, "10.0.0.2:9100", true, "10.0.0.2:9290", true, "10.0.0.3:9100", false),
                Map.of(), Map.of(),
                Map.of("10.0.0.1:9290", true, "10.0.0.2:9290", false),
                Map.of(), Map.of(), false));

        Map<String, List<RoleNamespace>> nsMappings = Map.of("ADMIN", List.of(new RoleNamespace(admin, "ns-a")));
        when(roleNamespaceRepository.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> names = inv.getArgument(0);
            return names.stream().flatMap(n -> nsMappings.getOrDefault(n, List.of()).stream()).toList();
        });
        when(tumblebugTreeBuilder.snapshot()).thenReturn(new TbSnapshot(Instant.now(),
                Map.of("ns-a", "Namespace A"),
                Map.of("ns-a", List.of(new TbInfra("infra-1", "infra-1", "Running-(3/3)", List.of(
                        new TbNode("vm-1", "vm-1", "Running", "g1", "aws"),
                        new TbNode("vm-2", "vm-2", "Terminated", "g1", "aws"),
                        new TbNode("vm-3", "vm-3", "Creating", null, "openstack"),
                        new TbNode("vm-4", "vm-4", "Failed", null, "openstack"))))),
                false));

        when(vmProvider.findByHost(any())).thenReturn(List.of());
        when(vmProvider.findByHost(active)).thenReturn(List.of(new VirtualResource("uuid-1", "web-01", "RUNNING", null)));

        service = new ResourceScopeService(authService, roleAssetRepository, roleNamespaceRepository,
                targetRepository, resolver, tumblebugTreeBuilder, vmProvider);
    }

    @Test
    void 물리_호스트_실행_상태는_자산상태_IPMI전원_exporter_up_순으로_판정한다() {
        Scope scope = service.scopeOf(List.of("ADMIN"));
        Map<String, ResourceStatus> byName = scope.hostsOf("ADMIN").stream()
                .collect(java.util.stream.Collectors.toMap(ScopedHost::name, ScopedHost::status));

        assertThat(byName).containsExactlyInAnyOrderEntriesOf(Map.of(
                "node-01", ResourceStatus.RUNNING,
                "node-02", ResourceStatus.STOP,                       // 섀시 전원 꺼짐
                "node-03", ResourceStatus.ERROR,                      // up=0
                "node-04", ResourceStatus.PENDING,                    // exporter 미등록
                "node-05", ResourceStatus.PENDING,                    // MAINTENANCE
                "node-06", ResourceStatus.STOP));                     // DECOMMISSIONED
        assertThat(byName).doesNotContainKey("san-01");               // 서버 유형만
    }

    @Test
    void 가상머신은_1세부_가상자원과_Tumblebug_VM을_버킷_상태로_합친다() {
        Scope scope = service.scopeOf(List.of("ADMIN"));
        Map<String, ResourceStatus> byId = scope.vmsOf("ADMIN").stream()
                .collect(java.util.stream.Collectors.toMap(ScopedVm::resourceId, ScopedVm::status));

        assertThat(byId).containsExactlyInAnyOrderEntriesOf(Map.of(
                "SEBU1:uuid-1", ResourceStatus.RUNNING,
                "TB:ns-a/infra-1/vm-1", ResourceStatus.RUNNING,
                "TB:ns-a/infra-1/vm-2", ResourceStatus.STOP,          // Terminated
                "TB:ns-a/infra-1/vm-3", ResourceStatus.PENDING,       // Creating
                "TB:ns-a/infra-1/vm-4", ResourceStatus.ERROR));       // Failed
    }

    @Test
    void 여러_역할에_매핑된_자원은_역할별_목록에는_각각_전체_집계에서는_한_번만_들어간다() {
        Scope scope = service.scopeOf(List.of("ADMIN", "USER"));

        assertThat(scope.hostsOf("USER")).extracting(ScopedHost::name).containsExactly("node-01");
        assertThat(scope.hosts()).filteredOn(h -> h.assetId() == 1L).hasSize(2);
        assertThat(scope.distinctHosts()).filteredOn(h -> h.assetId() == 1L).hasSize(1);
        assertThat(scope.distinctHosts()).hasSize(6);

        // 1세부 VM 도 역할별로 붙지만 resourceId 로 중복 제거
        assertThat(scope.vmsOf("USER")).extracting(ScopedVm::resourceId).containsExactly("SEBU1:uuid-1");
        assertThat(scope.distinctVms()).filteredOn(v -> v.resourceId().equals("SEBU1:uuid-1")).hasSize(1);
        assertThat(scope.mdcIds()).containsExactly("mdc-01", "mdc-02");
    }

    @Test
    void 현재_사용자_스코프는_토큰_역할을_쓰고_미인증이면_비어_있다() {
        when(authService.currentUserRoleIds()).thenReturn(Set.of());
        Scope empty = service.currentUserScope();
        assertThat(empty.roles()).isEmpty();
        assertThat(empty.hosts()).isEmpty();
        assertThat(empty.vms()).isEmpty();

        when(authService.currentUserRoleIds()).thenReturn(Set.of("USER"));
        assertThat(service.currentUserScope().hostsOf("USER")).hasSize(1);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────

    private static Asset asset(Long id, String mdcId, String name, Asset.AssetStatus status) {
        Asset asset = new Asset(mdcId, Asset.AssetType.SERVER, name);
        ReflectionTestUtils.setField(asset, "id", id);
        asset.setStatus(status);
        return asset;
    }

    private static MonitoringTarget target(Asset asset, String address, int port, ExporterType type) {
        MonitoringTarget target = new MonitoringTarget(asset.getMdcId(), asset.getName(), address, port, type);
        target.setAsset(asset);
        return target;
    }
}

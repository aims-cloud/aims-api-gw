package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.entity.RoleAsset;
import com.innogrid.aims.infra.repository.RoleAssetRepository;
import com.innogrid.aims.monitoring.ResourceExplorerProperties;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeFilter;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeType;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeSearchResponse;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MonitoringCapabilityResolver;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.PhysicalTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.Sebu1VirtualResourceProvider;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.Sebu1VirtualResourceProvider.VirtualResource;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 트리 서비스 단위 테스트 — 역할 스코프(role_assets), MDC 그룹화, exporter 기반 항목 구성,
 * Thanos 수집 상태 판정(docs/06 §9.4)을 검증한다. 원장·Thanos 는 Mockito 대역.
 *
 * 대역 구성:
 *   ADMIN → node-01(GPU_SERVER, mdc-01: node/dcgm/ipmi 모두 up), node-02(SERVER, mdc-02: node_exporter down), switch(제외)
 *   USER  → node-02, node-03(SERVER, mdc-02: exporter 미등록)
 */
class ResourceExplorerTreeServiceTest {

    private static final String NODE01_NE = "10.0.0.1:9100";
    private static final String NODE01_DCGM = "10.0.0.1:9400";
    private static final String NODE01_IPMI = "10.0.0.1:9290";
    private static final String NODE02_NE = "10.0.0.2:9100";

    RoleAssetRepository roleAssetRepository = mock(RoleAssetRepository.class);
    MonitoringTargetRepository targetRepository = mock(MonitoringTargetRepository.class);
    VictoriaMetricsClient metricsClient = mock(VictoriaMetricsClient.class);
    AuthService authService = mock(AuthService.class);
    Sebu1VirtualResourceProvider vmProvider = mock(Sebu1VirtualResourceProvider.class);
    TumblebugTreeBuilder tumblebugTreeBuilder = mock(TumblebugTreeBuilder.class);

    MutableClock clock = new MutableClock(Instant.parse("2026-09-04T09:00:00Z"));
    ResourceExplorerProperties properties = new ResourceExplorerProperties();
    MonitoringCapabilityResolver resolver;
    ResourceExplorerTreeService service;

    Asset node01;
    Asset node02;
    Asset node03;

    @BeforeEach
    void setUp() {
        Role admin = role(1L, "ADMIN");
        Role user = role(2L, "USER");
        node01 = asset(11L, "mdc-01", Asset.AssetType.GPU_SERVER, "node-01");
        node02 = asset(12L, "mdc-02", Asset.AssetType.SERVER, "node-02");
        node03 = asset(13L, "mdc-02", Asset.AssetType.SERVER, "node-03");
        Asset sanSwitch = asset(14L, "mdc-01", Asset.AssetType.SAN_SWITCH, "san-sw-01");

        Map<String, List<RoleAsset>> mappings = Map.of(
                "ADMIN", List.of(new RoleAsset(admin, node01), new RoleAsset(admin, node02), new RoleAsset(admin, sanSwitch)),
                "USER", List.of(new RoleAsset(user, node02), new RoleAsset(user, node03)));
        when(roleAssetRepository.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> names = inv.getArgument(0);
            return names.stream().flatMap(n -> mappings.getOrDefault(n, List.of()).stream()).toList();
        });

        List<MonitoringTarget> targets = List.of(
                target(node01, "10.0.0.1", 9100, ExporterType.NODE_EXPORTER),
                target(node01, "10.0.0.1", 9400, ExporterType.DCGM_EXPORTER),
                target(node01, "10.0.0.1", 9290, ExporterType.IPMI_EXPORTER),
                target(node02, "10.0.0.2", 9100, ExporterType.NODE_EXPORTER));
        when(targetRepository.findByAsset_IdIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return targets.stream().filter(t -> ids.contains(t.getAsset().getId())).toList();
        });

        stubThanosHealthy();
        when(vmProvider.findByHost(any())).thenReturn(List.of());
        when(tumblebugTreeBuilder.build(anyCollection())).thenReturn(Map.of());   // 가상머신 가지는 TumblebugTreeBuilderTest 에서 검증

        resolver = new MonitoringCapabilityResolver(metricsClient, properties, clock);
        PhysicalTreeBuilder builder = new PhysicalTreeBuilder(roleAssetRepository, targetRepository, resolver, vmProvider);
        service = new ResourceExplorerTreeService(builder, tumblebugTreeBuilder, authService);
    }

    private void stubThanosHealthy() {
        when(metricsClient.queryResults(MonitoringCapabilityResolver.UP_QUERY)).thenReturn(List.of(
                vector(Map.of("instance", NODE01_NE), "1"),
                vector(Map.of("instance", NODE01_DCGM), "1"),
                vector(Map.of("instance", NODE01_IPMI), "1"),
                vector(Map.of("instance", NODE02_NE), "0")));
        when(metricsClient.queryResults(MonitoringCapabilityResolver.gpuDeviceQuery(MonitoringCapabilityResolver.DEFAULT_PRESENCE_WINDOW))).thenReturn(List.of(
                vector(Map.of("instance", NODE01_DCGM, "UUID", "GPU-bbbb", "gpu", "1"), "1"),
                vector(Map.of("instance", NODE01_DCGM, "UUID", "GPU-aaaa", "gpu", "0"), "1")));
        when(metricsClient.queryResults(MonitoringCapabilityResolver.ipmiMetricQuery(MonitoringCapabilityResolver.DEFAULT_PRESENCE_WINDOW))).thenReturn(List.of(
                vector(Map.of("instance", NODE01_IPMI, "metric", "ipmi_temperature_celsius"), "12"),
                vector(Map.of("instance", NODE01_IPMI, "metric", "ipmi_fan_speed_rpm"), "6")));
    }

    private void loginWithRoles(String... roles) {
        when(authService.currentUserRoleIds()).thenReturn(new java.util.LinkedHashSet<>(List.of(roles)));
    }

    private static ResourceTreeFilter noFilter() {
        return new ResourceTreeFilter(null, null, null);
    }

    // ── 역할 스코프 ─────────────────────────────────────────────

    @Test
    void 트리는_토큰의_역할에_매핑된_자산만_노출한다() {
        loginWithRoles("ADMIN", "USER");
        ResourceTreeNodeResponse root = service.getTree(noFilter());
        assertThat(root.children()).extracting(ResourceTreeNodeResponse::name).containsExactly("ADMIN", "USER");

        loginWithRoles("USER");
        root = service.getTree(noFilter());
        assertThat(root.children()).extracting(ResourceTreeNodeResponse::name).containsExactly("USER");
        assertThat(hostNames(root.children().get(0))).containsExactly("node-02", "node-03");

        loginWithRoles("GUEST");                                      // role_assets 에 매핑이 없는 역할
        root = service.getTree(noFilter());
        ResourceTreeNodeResponse guest = root.children().get(0);      // 역할 노드는 항상 노출, 자원만 비어 있다
        assertThat(guest.name()).isEqualTo("GUEST");
        assertThat(guest.type()).isEqualTo(ResourceTreeNodeType.ROLE);
        assertThat(guest.leaf()).isTrue();
        assertThat(guest.children()).isEmpty();
    }

    @Test
    void 미인증이면_빈_트리를_반환한다() {
        when(authService.currentUserRoleIds()).thenReturn(Set.of());
        ResourceTreeNodeResponse root = service.getTree(noFilter());
        assertThat(root.children()).isEmpty();
        assertThat(root.leaf()).isTrue();
    }

    // ── 물리 가지 계층 ──────────────────────────────────────────

    @Test
    void 물리_가지는_역할_물리노드_MDC_호스트_계층으로_구성된다() {
        loginWithRoles("ADMIN");
        ResourceTreeNodeResponse role = service.getTree(noFilter()).children().get(0);
        assertThat(role.id()).isEqualTo("role:ADMIN");

        ResourceTreeNodeResponse physical = role.children().get(0);
        assertThat(role.children()).hasSize(1);
        assertThat(physical.type()).isEqualTo(ResourceTreeNodeType.PHYSICAL_ROOT);
        assertThat(physical.id()).isEqualTo("physical:ADMIN");

        assertThat(physical.children()).extracting(ResourceTreeNodeResponse::type)
                .containsOnly(ResourceTreeNodeType.MDC);
        assertThat(physical.children()).extracting(ResourceTreeNodeResponse::name).containsExactly("mdc-01", "mdc-02");
        assertThat(physical.children()).extracting(ResourceTreeNodeResponse::mdcId).containsExactly("mdc-01", "mdc-02");

        ResourceTreeNodeResponse mdc01 = physical.children().get(0);
        assertThat(mdc01.id()).isEqualTo("mdc:ADMIN:mdc-01");
        // SAN 스위치는 물리 호스트가 아니므로 제외된다
        assertThat(mdc01.children()).extracting(ResourceTreeNodeResponse::name).containsExactly("node-01");

        ResourceTreeNodeResponse host = mdc01.children().get(0);
        assertThat(host.id()).isEqualTo("host:ADMIN:11");
        assertThat(host.resourceId()).isEqualTo("11");                // assets.id
        assertThat(host.status()).isEqualTo("ACTIVE");                // 자원 상태
        assertThat(host.monitoringStatus()).isEqualTo(MonitoringStatus.COLLECTING);
    }

    @Test
    void 역할_하위에_물리_가지와_가상머신_가지가_함께_붙는다() {
        ResourceTreeNodeResponse vmRoot = ResourceTreeNodeResponse.of("vmroot:ADMIN", "role:ADMIN", "가상머신",
                ResourceTreeNodeType.VM_ROOT, null, "ADMIN", null, null, null, List.of());
        when(tumblebugTreeBuilder.build(anyCollection())).thenReturn(Map.of("ADMIN", vmRoot));
        loginWithRoles("ADMIN", "USER");

        ResourceTreeNodeResponse root = service.getTree(noFilter());
        assertThat(root.children().get(0).children()).extracting(ResourceTreeNodeResponse::type)
                .containsExactly(ResourceTreeNodeType.PHYSICAL_ROOT, ResourceTreeNodeType.VM_ROOT);
        assertThat(root.children().get(1).children()).extracting(ResourceTreeNodeResponse::type)
                .containsExactly(ResourceTreeNodeType.PHYSICAL_ROOT);          // USER 는 Namespace 매핑 없음

        // MDC 필터는 물리 가지 전용 — 걸려 있으면 가상머신 가지는 생략된다
        root = service.getTree(new ResourceTreeFilter(null, List.of("mdc-01"), null));
        assertThat(root.children().get(0).children()).extracting(ResourceTreeNodeResponse::type)
                .containsExactly(ResourceTreeNodeType.PHYSICAL_ROOT);
    }

    @Test
    void 모니터링_항목은_등록된_exporter와_확인된_메트릭으로_구성된다() {
        loginWithRoles("ADMIN");
        ResourceTreeNodeResponse node01 = host(service.getTree(noFilter()), "node-01");

        assertThat(node01.children()).extracting(ResourceTreeNodeResponse::name).containsExactly(
                "CPU", "Memory", "Disk", "Network", "Process", "Service Status", "Swap", "System", "GPU", "Hardware");
        assertThat(node01.children()).extracting(ResourceTreeNodeResponse::monitoringStatus)
                .containsOnly(MonitoringStatus.COLLECTING);

        ResourceTreeNodeResponse disk = node01.children().get(2);
        assertThat(disk.children()).extracting(ResourceTreeNodeResponse::type)
                .containsExactly(ResourceTreeNodeType.DISK_IO, ResourceTreeNodeType.FILE_SYSTEM);

        // GPU 장치는 gpu 라벨 순으로 정렬되고 UUID 가 resourceId
        ResourceTreeNodeResponse gpu = node01.children().get(8);
        assertThat(gpu.children()).extracting(ResourceTreeNodeResponse::name).containsExactly("GPU 0", "GPU 1");
        assertThat(gpu.children()).extracting(ResourceTreeNodeResponse::resourceId).containsExactly("GPU-aaaa", "GPU-bbbb");

        // Hardware 는 센서 메트릭이 확인된 항목만 (Temperature, Fan) — 나머지는 NO_DATA 로 미생성
        ResourceTreeNodeResponse hardware = node01.children().get(9);
        assertThat(hardware.children()).extracting(ResourceTreeNodeResponse::type)
                .containsExactly(ResourceTreeNodeType.HW_TEMPERATURE, ResourceTreeNodeType.HW_FAN);
    }

    @Test
    void up이_0이면_항목은_유지하고_UNAVAILABLE로_표시한다() {
        loginWithRoles("ADMIN");
        ResourceTreeNodeResponse node02 = host(service.getTree(noFilter()), "node-02");

        assertThat(node02.monitoringStatus()).isEqualTo(MonitoringStatus.UNAVAILABLE);
        assertThat(node02.children()).hasSize(8);                     // node_exporter 항목 8종 유지
        assertThat(node02.children()).extracting(ResourceTreeNodeResponse::monitoringStatus)
                .containsOnly(MonitoringStatus.UNAVAILABLE);
    }

    @Test
    void exporter가_등록되지_않은_호스트는_항목_없이_NOT_CONFIGURED다() {
        loginWithRoles("USER");
        ResourceTreeNodeResponse node03 = host(service.getTree(noFilter()), "node-03");

        assertThat(node03.monitoringStatus()).isEqualTo(MonitoringStatus.NOT_CONFIGURED);
        assertThat(node03.leaf()).isTrue();
        assertThat(node03.children()).isEmpty();
    }

    @Test
    void dcgm_ipmi가_일시_중단되어도_GPU_Hardware_항목은_유지되고_UNAVAILABLE로_표시된다() {
        // 존재는 24시간 이력(count_over_time)으로 보고, 현재 up 만 0 → 항목 유지 + 미수집 (docs/06 §5.1)
        when(metricsClient.queryResults(MonitoringCapabilityResolver.UP_QUERY)).thenReturn(List.of(
                vector(Map.of("instance", NODE01_NE), "1"),
                vector(Map.of("instance", NODE01_DCGM), "0"),
                vector(Map.of("instance", NODE02_NE), "0")));          // NODE01_IPMI 는 up 자체가 사라짐(5분 초과)
        loginWithRoles("ADMIN");
        ResourceTreeNodeResponse node01 = host(service.getTree(noFilter()), "node-01");

        ResourceTreeNodeResponse gpu = node01.children().get(8);
        assertThat(gpu.type()).isEqualTo(ResourceTreeNodeType.GPU_GROUP);
        assertThat(gpu.monitoringStatus()).isEqualTo(MonitoringStatus.UNAVAILABLE);
        assertThat(gpu.children()).hasSize(2);
        ResourceTreeNodeResponse hardware = node01.children().get(9);
        assertThat(hardware.type()).isEqualTo(ResourceTreeNodeType.HARDWARE);
        assertThat(hardware.monitoringStatus()).isEqualTo(MonitoringStatus.UNAVAILABLE);
        assertThat(hardware.children()).hasSize(2);
    }

    @Test
    void GPU_장치_메트릭이_없으면_GPU_그룹을_만들지_않는다() {
        when(metricsClient.queryResults(MonitoringCapabilityResolver.gpuDeviceQuery(MonitoringCapabilityResolver.DEFAULT_PRESENCE_WINDOW))).thenReturn(List.of());
        loginWithRoles("ADMIN");
        ResourceTreeNodeResponse node01 = host(service.getTree(noFilter()), "node-01");
        assertThat(node01.children()).extracting(ResourceTreeNodeResponse::type)
                .doesNotContain(ResourceTreeNodeType.GPU_GROUP);
    }

    @Test
    void 물리_호스트_하위에_1세부_가상자원을_연결한다() {
        when(vmProvider.findByHost(node01)).thenReturn(List.of(
                new VirtualResource("vm-uuid-1", "web-01", "RUNNING", "10.0.0.1:9100"),
                new VirtualResource("vm-uuid-2", "web-02", "RUNNING", null)));   // exporter 미설치
        loginWithRoles("ADMIN");
        ResourceTreeNodeResponse node01 = host(service.getTree(noFilter()), "node-01");

        ResourceTreeNodeResponse group = node01.children().get(node01.children().size() - 1);
        assertThat(group.type()).isEqualTo(ResourceTreeNodeType.VIRTUAL_RESOURCE_GROUP);
        assertThat(group.children()).extracting(ResourceTreeNodeResponse::resourceId)
                .containsExactly("SEBU1:vm-uuid-1", "SEBU1:vm-uuid-2");

        ResourceTreeNodeResponse web01 = group.children().get(0);
        assertThat(web01.status()).isEqualTo("RUNNING");
        assertThat(web01.monitoringStatus()).isEqualTo(MonitoringStatus.COLLECTING);
        assertThat(web01.children()).hasSize(8);

        ResourceTreeNodeResponse web02 = group.children().get(1);
        assertThat(web02.monitoringStatus()).isEqualTo(MonitoringStatus.NOT_CONFIGURED);
        assertThat(web02.leaf()).isTrue();
    }

    // ── Thanos 스냅샷 캐시 ──────────────────────────────────────

    @Test
    void Thanos_스냅샷은_TTL_동안_재사용하고_장애시_마지막_정상_결과를_유지한다() {
        loginWithRoles("ADMIN");
        assertThat(host(service.getTree(noFilter()), "node-01").monitoringStatus()).isEqualTo(MonitoringStatus.COLLECTING);

        // TTL(1분) 안 — Thanos 가 죽어도 캐시된 스냅샷으로 응답한다
        when(metricsClient.queryResults(any())).thenThrow(new IllegalStateException("thanos down"));
        clock.advance(Duration.ofSeconds(30));
        assertThat(host(service.getTree(noFilter()), "node-01").monitoringStatus()).isEqualTo(MonitoringStatus.COLLECTING);

        // TTL 만료 후 재조회 실패 — 마지막 정상 결과를 유지한다
        clock.advance(Duration.ofSeconds(40));
        ResourceTreeNodeResponse node01 = host(service.getTree(noFilter()), "node-01");
        assertThat(node01.monitoringStatus()).isEqualTo(MonitoringStatus.COLLECTING);
        assertThat(node01.children()).extracting(ResourceTreeNodeResponse::type).contains(ResourceTreeNodeType.GPU_GROUP);
    }

    @Test
    void 정상_스냅샷이_없는_상태에서_Thanos_장애면_항목은_UNAVAILABLE이고_GPU_Hardware는_만들지_않는다() {
        when(metricsClient.queryResults(any())).thenThrow(new IllegalStateException("thanos down"));
        loginWithRoles("ADMIN");
        ResourceTreeNodeResponse node01 = host(service.getTree(noFilter()), "node-01");

        assertThat(node01.monitoringStatus()).isEqualTo(MonitoringStatus.UNAVAILABLE);
        assertThat(node01.children()).hasSize(8);
        assertThat(node01.children()).extracting(ResourceTreeNodeResponse::monitoringStatus)
                .containsOnly(MonitoringStatus.UNAVAILABLE);
    }

    // ── 필터·검색·Lazy ───────────────────────────────────────────

    @Test
    void MDC_필터는_해당_MDC의_호스트만_남긴다() {
        loginWithRoles("ADMIN");
        ResourceTreeNodeResponse root = service.getTree(new ResourceTreeFilter(null, List.of("mdc-02"), null));
        ResourceTreeNodeResponse physical = root.children().get(0).children().get(0);
        assertThat(physical.children()).extracting(ResourceTreeNodeResponse::name).containsExactly("mdc-02");

        // 필터에 걸리는 MDC 가 하나도 없으면 역할 노드만 남고 하위는 비어 있다
        root = service.getTree(new ResourceTreeFilter(null, List.of("mdc-99"), null));
        assertThat(root.children()).extracting(ResourceTreeNodeResponse::name).containsExactly("ADMIN");
        assertThat(root.children().get(0).leaf()).isTrue();
    }

    @Test
    void 키워드_필터는_일치_항목의_상위_계층을_유지한다() {
        loginWithRoles("ADMIN");
        ResourceTreeNodeResponse root = service.getTree(new ResourceTreeFilter(null, null, "node-02"));

        ResourceTreeNodeResponse physical = root.children().get(0).children().get(0);
        assertThat(physical.children()).extracting(ResourceTreeNodeResponse::name).containsExactly("mdc-02");
        ResourceTreeNodeResponse node02 = physical.children().get(0).children().get(0);
        assertThat(node02.name()).isEqualTo("node-02");
        assertThat(node02.children()).hasSize(8);                     // 일치 노드의 하위는 원본 유지
    }

    @Test
    void 자식_조회는_트리_항목_ID_체계로_하위를_해석한다() {
        loginWithRoles("ADMIN");
        List<ResourceTreeNodeResponse> roots = service.getRoots(noFilter());
        assertThat(roots).hasSize(1);
        assertThat(roots.get(0).children()).isNull();                 // lazy — 자식 미포함
        assertThat(roots.get(0).childCount()).isEqualTo(1);

        assertThat(service.getChildren("role:ADMIN")).extracting(ResourceTreeNodeResponse::id)
                .containsExactly("physical:ADMIN");
        assertThat(service.getChildren("mdc:ADMIN:mdc-01")).extracting(ResourceTreeNodeResponse::id)
                .containsExactly("host:ADMIN:11");
        assertThat(service.getChildren("gpus:host:ADMIN:11")).extracting(ResourceTreeNodeResponse::name)
                .containsExactly("GPU 0", "GPU 1");
    }

    @Test
    void 트리_검색은_상위_경로를_함께_반환한다() {
        loginWithRoles("ADMIN");
        List<ResourceTreeSearchResponse> results = service.search(
                "node-01", List.of(ResourceTreeNodeType.PHYSICAL_HOST), 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).node().name()).isEqualTo("node-01");
        assertThat(results.get(0).path()).extracting(p -> p.type().name())
                .containsExactly("ROOT", "ROLE", "PHYSICAL_ROOT", "MDC");
    }

    // ── 헬퍼 ────────────────────────────────────────────────────

    private static Role role(Long id, String name) {
        Role role = new Role(name, name + " 역할");
        ReflectionTestUtils.setField(role, "id", id);
        return role;
    }

    private static Asset asset(Long id, String mdcId, Asset.AssetType type, String name) {
        Asset asset = new Asset(mdcId, type, name);
        ReflectionTestUtils.setField(asset, "id", id);
        return asset;
    }

    private static MonitoringTarget target(Asset asset, String address, int port, ExporterType type) {
        MonitoringTarget target = new MonitoringTarget(asset.getMdcId(), asset.getName(), address, port, type);
        target.setAsset(asset);
        return target;
    }

    private static Map<String, Object> vector(Map<String, String> labels, String value) {
        return Map.of("metric", labels, "value", List.of(1_757_000_000, value));
    }

    private static ResourceTreeNodeResponse host(ResourceTreeNodeResponse root, String name) {
        return root.children().stream()
                .flatMap(role -> role.children().stream())
                .flatMap(physical -> physical.children().stream())
                .flatMap(mdc -> mdc.children().stream())
                .filter(h -> h.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("호스트 없음: " + name));
    }

    private static List<String> hostNames(ResourceTreeNodeResponse role) {
        return role.children().stream()
                .flatMap(physical -> physical.children().stream())
                .flatMap(mdc -> mdc.children().stream())
                .map(ResourceTreeNodeResponse::name)
                .toList();
    }

    /** 테스트에서 시간을 앞당길 수 있는 Clock */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

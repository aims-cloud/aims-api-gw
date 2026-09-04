package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.cloudbarista.tumblebug.TumblebugClient;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.ResourceExplorerProperties;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 가상머신 가지 조립 테스트 — role_namespaces 스코프, Namespace→Infra→NodeGroup/미분류→Node 계층,
 * Tumblebug 상태값 원문 노출, providerName 배지, Thanos tb_* 라벨 조인, 1분 캐시와 장애 시 마지막 정상값 유지.
 *
 * 대역 구성 (CB-Tumblebug OpenAPI 응답 형태):
 *   ns-a "Namespace A": infra-1 (vm-1 g1 aws Running / vm-2 g1 aws Terminated / vm-3 미분류 openstack Creating), infra-2 (vm-4 g2 azure Running)
 *   ns-b: Infra 없음
 *   ADMIN → ns-a, ns-b, ns-x(Tumblebug 에 없음) / USER → 매핑 없음
 */
class TumblebugTreeBuilderTest {

    TumblebugClient tumblebugClient = mock(TumblebugClient.class);
    RoleNamespaceRepository roleNamespaceRepository = mock(RoleNamespaceRepository.class);
    VictoriaMetricsClient metricsClient = mock(VictoriaMetricsClient.class);

    MutableClock clock = new MutableClock(Instant.parse("2026-09-04T09:00:00Z"));
    ResourceExplorerProperties properties = new ResourceExplorerProperties();
    TumblebugTreeBuilder builder;

    @BeforeEach
    void setUp() {
        Role admin = new Role("ADMIN", "관리자");
        Map<String, List<RoleNamespace>> mappings = Map.of(
                "ADMIN", List.of(new RoleNamespace(admin, "ns-a"), new RoleNamespace(admin, "ns-b"), new RoleNamespace(admin, "ns-x")));
        when(roleNamespaceRepository.findByRole_NameIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> names = inv.getArgument(0);
            return names.stream().flatMap(n -> mappings.getOrDefault(n, List.of()).stream()).toList();
        });

        stubTumblebugHealthy();
        when(metricsClient.queryResults(any())).thenReturn(List.of());
        when(metricsClient.queryResults(MonitoringCapabilityResolver.TB_UP_QUERY)).thenReturn(List.of(
                vector(Map.of("tb_ns", "ns-a", "tb_infra", "infra-1", "tb_node", "vm-1"), "1"),
                vector(Map.of("tb_ns", "ns-a", "tb_infra", "infra-1", "tb_node", "vm-2"), "0")));
        when(metricsClient.queryResults(MonitoringCapabilityResolver.TB_GPU_DEVICE_QUERY)).thenReturn(List.of(
                vector(Map.of("tb_ns", "ns-a", "tb_infra", "infra-1", "tb_node", "vm-1", "UUID", "GPU-1111", "gpu", "0"), "1")));

        MonitoringCapabilityResolver resolver = new MonitoringCapabilityResolver(metricsClient, properties, clock);
        builder = new TumblebugTreeBuilder(tumblebugClient, roleNamespaceRepository, resolver, properties, clock);
    }

    private void stubTumblebugHealthy() {
        when(tumblebugClient.listNamespaces()).thenReturn(Map.of("ns", List.of(
                Map.of("id", "ns-a", "name", "Namespace A", "uid", "u-a"),
                Map.of("id", "ns-b", "name", "Namespace B", "uid", "u-b"))));
        when(tumblebugClient.listInfra("ns-a")).thenReturn(Map.of("infra", List.of(
                Map.of("id", "infra-1", "name", "infra-1", "status", "Running-(3/3)", "node", List.of(
                        node("vm-2", "Terminated", "g1", "aws"),
                        node("vm-1", "Running", "g1", "aws"),
                        node("vm-3", "Creating", null, "openstack"))),
                Map.of("id", "infra-2", "name", "infra-2", "status", "Running-(1/1)", "node", List.of(
                        node("vm-4", "Running", "g2", "azure"))))));
        when(tumblebugClient.listInfra("ns-b")).thenReturn(Map.of("infra", List.of()));
    }

    // ── 계층·식별자 ─────────────────────────────────────────────

    @Test
    void 역할에_매핑된_Namespace만_가상머신_가지에_포함한다() {
        Map<String, ResourceTreeNodeResponse> result = builder.build(List.of("ADMIN", "USER"));

        assertThat(result).containsOnlyKeys("ADMIN");                 // USER 는 매핑 없음
        ResourceTreeNodeResponse vmRoot = result.get("ADMIN");
        assertThat(vmRoot.id()).isEqualTo("vmroot:ADMIN");
        assertThat(vmRoot.parentId()).isEqualTo("role:ADMIN");
        assertThat(vmRoot.type()).isEqualTo(ResourceTreeNodeType.VM_ROOT);
        assertThat(vmRoot.status()).isNull();                         // 정상 조회
        // ns-x 는 Tumblebug 에 없어 건너뛰고, ns-b 는 Infra 가 없어도 Namespace 노드는 남는다
        assertThat(vmRoot.children()).extracting(ResourceTreeNodeResponse::name).containsExactly("Namespace A", "Namespace B");
        assertThat(vmRoot.children()).extracting(ResourceTreeNodeResponse::resourceId).containsExactly("TB:ns-a", "TB:ns-b");
        assertThat(vmRoot.children().get(1).leaf()).isTrue();
    }

    @Test
    void Namespace_Infra_NodeGroup_미분류_Node_계층과_resourceId를_구성한다() {
        ResourceTreeNodeResponse nsA = builder.build(List.of("ADMIN")).get("ADMIN").children().get(0);
        assertThat(nsA.id()).isEqualTo("ns:ADMIN:ns-a");

        assertThat(nsA.children()).extracting(ResourceTreeNodeResponse::type).containsOnly(ResourceTreeNodeType.INFRA);
        ResourceTreeNodeResponse infra1 = nsA.children().get(0);
        assertThat(infra1.id()).isEqualTo("infra:ADMIN:ns-a:infra-1");
        assertThat(infra1.resourceId()).isEqualTo("TB:ns-a/infra-1");
        assertThat(infra1.status()).isEqualTo("Running-(3/3)");

        // NodeGroup 은 이름순, 미분류는 마지막
        assertThat(infra1.children()).extracting(ResourceTreeNodeResponse::type)
                .containsExactly(ResourceTreeNodeType.NODE_GROUP, ResourceTreeNodeType.UNCLASSIFIED);
        ResourceTreeNodeResponse g1 = infra1.children().get(0);
        assertThat(g1.name()).isEqualTo("g1");
        assertThat(g1.id()).isEqualTo("ng:ADMIN:ns-a:infra-1:g1");
        assertThat(g1.resourceId()).isEqualTo("TB:ns-a/infra-1/@g1");
        ResourceTreeNodeResponse unclassified = infra1.children().get(1);
        assertThat(unclassified.name()).isEqualTo("미분류");
        assertThat(unclassified.resourceId()).isEqualTo("TB:ns-a/infra-1/@unclassified");
        assertThat(unclassified.children()).extracting(ResourceTreeNodeResponse::name).containsExactly("vm-3");

        // Node 는 이름순, resourceId 는 TB:{ns}/{infra}/{node}
        assertThat(g1.children()).extracting(ResourceTreeNodeResponse::name).containsExactly("vm-1", "vm-2");
        ResourceTreeNodeResponse vm1 = g1.children().get(0);
        assertThat(vm1.id()).isEqualTo("tbvm:ADMIN:ns-a:infra-1:vm-1");
        assertThat(vm1.resourceId()).isEqualTo("TB:ns-a/infra-1/vm-1");
        assertThat(vm1.type()).isEqualTo(ResourceTreeNodeType.VIRTUAL_MACHINE);
    }

    @Test
    void Tumblebug_상태값은_원문으로_노출하고_Terminated도_제외하지_않는다() {
        ResourceTreeNodeResponse infra1 = builder.build(List.of("ADMIN")).get("ADMIN").children().get(0).children().get(0);
        List<ResourceTreeNodeResponse> g1 = infra1.children().get(0).children();

        assertThat(g1).extracting(ResourceTreeNodeResponse::status).containsExactly("Running", "Terminated");
        assertThat(infra1.children().get(1).children().get(0).status()).isEqualTo("Creating");
    }

    @Test
    void 트리에는_providerName만_싣고_connection_정보는_싣지_않는다() {
        ResourceTreeNodeResponse infra1 = builder.build(List.of("ADMIN")).get("ADMIN").children().get(0).children().get(0);

        assertThat(infra1.children().get(0).children()).extracting(ResourceTreeNodeResponse::provider).containsExactly("aws", "aws");
        assertThat(infra1.children().get(1).children().get(0).provider()).isEqualTo("openstack");
        assertThat(infra1.provider()).isNull();                       // Infra 는 connection 별로 복제·표기하지 않는다
    }

    // ── Thanos 조인 ─────────────────────────────────────────────

    @Test
    void 모니터링_항목은_tb_라벨_시계열_존재와_up으로_판정한다() {
        ResourceTreeNodeResponse infra1 = builder.build(List.of("ADMIN")).get("ADMIN").children().get(0).children().get(0);
        ResourceTreeNodeResponse vm1 = infra1.children().get(0).children().get(0);
        ResourceTreeNodeResponse vm2 = infra1.children().get(0).children().get(1);
        ResourceTreeNodeResponse vm3 = infra1.children().get(1).children().get(0);

        // vm-1: up=1 → COLLECTING, node_exporter 8종 + GPU 그룹(장치 1)
        assertThat(vm1.monitoringStatus()).isEqualTo(MonitoringStatus.COLLECTING);
        assertThat(vm1.children()).extracting(ResourceTreeNodeResponse::type)
                .containsExactly(ResourceTreeNodeType.CPU, ResourceTreeNodeType.MEMORY, ResourceTreeNodeType.DISK,
                        ResourceTreeNodeType.NETWORK, ResourceTreeNodeType.PROCESS, ResourceTreeNodeType.SERVICE_STATUS,
                        ResourceTreeNodeType.SWAP, ResourceTreeNodeType.SYSTEM, ResourceTreeNodeType.GPU_GROUP);
        assertThat(vm1.children().get(8).children()).extracting(ResourceTreeNodeResponse::resourceId).containsExactly("GPU-1111");

        // vm-2: up=0 → 항목 유지 + UNAVAILABLE
        assertThat(vm2.monitoringStatus()).isEqualTo(MonitoringStatus.UNAVAILABLE);
        assertThat(vm2.children()).hasSize(8);
        assertThat(vm2.children()).extracting(ResourceTreeNodeResponse::monitoringStatus).containsOnly(MonitoringStatus.UNAVAILABLE);

        // vm-3: 시계열 없음 → NOT_CONFIGURED, 항목 없음
        assertThat(vm3.monitoringStatus()).isEqualTo(MonitoringStatus.NOT_CONFIGURED);
        assertThat(vm3.leaf()).isTrue();
    }

    // ── 캐시·장애 ───────────────────────────────────────────────

    @Test
    void Tumblebug_조회는_TTL_동안_재사용한다() {
        builder.build(List.of("ADMIN"));
        clock.advance(Duration.ofSeconds(30));
        builder.build(List.of("ADMIN"));
        verify(tumblebugClient, times(1)).listNamespaces();          // 1분 안에서는 한 번만 조회

        clock.advance(Duration.ofSeconds(40));
        builder.build(List.of("ADMIN"));
        verify(tumblebugClient, times(2)).listNamespaces();          // TTL 만료 후 재조회
    }

    @Test
    void 갱신_실패시_마지막_정상_결과를_유지하고_조회_실패_상태를_표시한다() {
        assertThat(builder.build(List.of("ADMIN")).get("ADMIN").status()).isNull();

        when(tumblebugClient.listNamespaces()).thenThrow(new IllegalStateException("tumblebug down"));
        clock.advance(Duration.ofSeconds(70));
        ResourceTreeNodeResponse vmRoot = builder.build(List.of("ADMIN")).get("ADMIN");

        assertThat(vmRoot.status()).isEqualTo(TumblebugTreeBuilder.UNAVAILABLE_STATUS);
        assertThat(vmRoot.children()).extracting(ResourceTreeNodeResponse::name).containsExactly("Namespace A", "Namespace B");
    }

    @Test
    void 정상_결과가_없는_상태에서_실패하면_빈_가상머신_노드에_조회_실패_상태만_표시한다() {
        when(tumblebugClient.listNamespaces()).thenThrow(new IllegalStateException("tumblebug down"));
        Map<String, ResourceTreeNodeResponse> result = builder.build(List.of("ADMIN", "USER"));

        assertThat(result).containsOnlyKeys("ADMIN");                 // 매핑이 없는 USER 는 그래도 제외
        assertThat(result.get("ADMIN").status()).isEqualTo(TumblebugTreeBuilder.UNAVAILABLE_STATUS);
        assertThat(result.get("ADMIN").leaf()).isTrue();
    }

    // ── 헬퍼 ────────────────────────────────────────────────────

    private static Map<String, Object> node(String id, String status, String nodeGroupId, String provider) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("name", id);
        node.put("status", status);
        node.put("nodeGroupId", nodeGroupId == null ? "" : nodeGroupId);
        node.put("connectionName", provider + "-conn-01");
        node.put("connectionConfig", Map.of("providerName", provider, "regionZoneInfoName", "ap-northeast-2"));
        node.put("cspResourceId", "csp-" + id);
        node.put("uid", "uid-" + id);
        return node;
    }

    private static Map<String, Object> vector(Map<String, String> labels, String value) {
        return Map.of("metric", labels, "value", List.of(1_757_000_000, value));
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

package com.innogrid.aims.dashboard.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.infra.repository.RackRepository;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.repository.EventRepository;
import com.innogrid.aims.monitoring.repository.MonitoringTargetRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardTopologyTest {

    private MonitoringTargetRepository targetRepository;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        targetRepository = mock(MonitoringTargetRepository.class);
        service = new DashboardService(
                mock(AssetRepository.class),
                mock(RackRepository.class),
                targetRepository,
                mock(EventRepository.class),
                mock(VictoriaMetricsClient.class));
    }

    private static MonitoringTarget target(String mdcId, String hostname,
                                           ExporterType type, String alias) {
        MonitoringTarget target = new MonitoringTarget(mdcId, hostname, "10.0.0.1", 9100, type);
        target.setAlias(alias);
        return target;
    }

    @Test
    void compute_view_shows_compute_nodes_with_alias() {
        when(targetRepository.findAll()).thenReturn(List.of(
                target("mdc-1", "node-01", ExporterType.NODE_EXPORTER, "웹서버1"),
                target("mdc-1", "gpu-01", ExporterType.DCGM_EXPORTER, null),
                target("mdc-1", "switch-01", ExporterType.SNMP_EXPORTER, null)));

        Map<String, Object> topology = service.getTopology(null, "compute");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) topology.get("nodes");
        // MDC 1개 + 컴퓨트 노드 2개 (SNMP 스위치는 네트워크 뷰 전용)
        assertThat(nodes).hasSize(3);

        Map<String, Object> aliased = nodes.stream()
                .filter(n -> "node-01".equals(n.get("label"))).findFirst().orElseThrow();
        assertThat(aliased)
                .containsEntry("alias", "웹서버1")
                .containsEntry("displayName", "웹서버1")
                .containsEntry("type", "COMPUTE");

        Map<String, Object> noAlias = nodes.stream()
                .filter(n -> "gpu-01".equals(n.get("label"))).findFirst().orElseThrow();
        assertThat(noAlias).containsEntry("displayName", "gpu-01");
    }

    @Test
    void network_view_shows_switches_and_mdc_links() {
        when(targetRepository.findAll()).thenReturn(List.of(
                target("mdc-1", "node-01", ExporterType.NODE_EXPORTER, null),
                target("mdc-1", "switch-01", ExporterType.SNMP_EXPORTER, null),
                target("mdc-2", "node-02", ExporterType.NODE_EXPORTER, null)));

        Map<String, Object> topology = service.getTopology(null, "network");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) topology.get("nodes");
        // MDC 2개 + 네트워크 장비 1개 (컴퓨트 노드 제외)
        assertThat(nodes).hasSize(3);
        assertThat(nodes.stream().filter(n -> "NETWORK".equals(n.get("type")))).hasSize(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) topology.get("edges");
        // 스위치 연결 1개 + MDC 간 연결 1개
        assertThat(edges).hasSize(2);
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.get("from")).isEqualTo("mdc-1");
            assertThat(edge.get("to")).isEqualTo("mdc-2");
        });
    }

    @Test
    void mdc_filter_limits_topology_to_one_zone() {
        when(targetRepository.findAll()).thenReturn(List.of(
                target("mdc-1", "node-01", ExporterType.NODE_EXPORTER, null),
                target("mdc-2", "node-02", ExporterType.NODE_EXPORTER, null)));

        Map<String, Object> topology = service.getTopology("mdc-1", "compute");

        assertThat(topology).containsEntry("mdcFilter", "mdc-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) topology.get("nodes");
        assertThat(nodes).hasSize(2); // MDC 1개 + 노드 1개
        assertThat(nodes).noneSatisfy(n -> assertThat(n.get("mdcId")).isEqualTo("mdc-2"));
    }

    @Test
    void legacy_network_topology_endpoint_still_works() {
        when(targetRepository.findAll()).thenReturn(List.of(
                target("mdc-1", "node-01", ExporterType.NODE_EXPORTER, null)));

        Map<String, Object> topology = service.getNetworkTopology();

        assertThat(topology).containsEntry("view", "network");
        assertThat(topology.get("nodes")).isNotNull();
    }
}

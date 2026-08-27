package com.innogrid.aims.openstack.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.openstack.NeutronClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubnetServiceTest {

    private NeutronClient neutron;
    private SubnetService service;

    @BeforeEach
    void setUp() {
        neutron = mock(NeutronClient.class);
        service = new SubnetService(neutron);
    }

    private static Map<String, Object> samplePort(String portId, String subnetId, String ip) {
        return Map.of(
                "id", portId,
                "status", "ACTIVE",
                "mac_address", "fa:16:3e:00:00:01",
                "device_owner", "compute:nova",
                "device_id", "vm-1",
                "network_id", "net-1",
                "fixed_ips", List.of(Map.of("subnet_id", subnetId, "ip_address", ip)));
    }

    @Test
    void list_subnets_without_filter_queries_all() {
        when(neutron.listSubnets()).thenReturn(Map.of("subnets", List.of(
                Map.of("id", "sub-1", "cidr", "10.0.1.0/24", "network_id", "net-1"))));

        List<Map<String, Object>> subnets = service.listSubnets(null);

        assertThat(subnets).hasSize(1);
        assertThat(subnets.get(0)).containsEntry("cidr", "10.0.1.0/24");
        verify(neutron).listSubnets();
    }

    @Test
    void list_subnets_with_network_filter_queries_by_network() {
        when(neutron.listSubnetsByNetwork("net-1")).thenReturn(Map.of("subnets", List.of(
                Map.of("id", "sub-1", "network_id", "net-1"))));

        List<Map<String, Object>> subnets = service.listSubnets("net-1");

        assertThat(subnets).hasSize(1);
        verify(neutron).listSubnetsByNetwork("net-1");
    }

    @Test
    void get_subnet_throws_not_found_when_missing() {
        when(neutron.getSubnet("nope")).thenReturn(Map.of());

        assertThatThrownBy(() -> service.getSubnet("nope"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void subnet_ips_tab_flattens_ports_to_ip_rows() {
        when(neutron.listPortsBySubnet("sub-1")).thenReturn(Map.of("ports", List.of(
                samplePort("port-1", "sub-1", "10.0.1.5"),
                samplePort("port-2", "sub-1", "10.0.1.6"))));

        List<Map<String, Object>> ips = service.getSubnetIps("sub-1");

        assertThat(ips).hasSize(2);
        assertThat(ips.get(0))
                .containsEntry("ipAddress", "10.0.1.5")
                .containsEntry("subnetId", "sub-1")
                .containsEntry("portId", "port-1")
                .containsEntry("deviceId", "vm-1");
    }

    @Test
    void subnet_ips_excludes_other_subnet_addresses_on_multi_ip_port() {
        Map<String, Object> multiIpPort = Map.of(
                "id", "port-1",
                "network_id", "net-1",
                "fixed_ips", List.of(
                        Map.of("subnet_id", "sub-1", "ip_address", "10.0.1.5"),
                        Map.of("subnet_id", "sub-2", "ip_address", "10.0.2.5")));
        when(neutron.listPortsBySubnet("sub-1"))
                .thenReturn(Map.of("ports", List.of(multiIpPort)));

        List<Map<String, Object>> ips = service.getSubnetIps("sub-1");

        assertThat(ips).hasSize(1);
        assertThat(ips.get(0)).containsEntry("ipAddress", "10.0.1.5");
    }

    @Test
    void list_ips_routes_to_correct_neutron_query_by_filter() {
        when(neutron.listPorts()).thenReturn(Map.of("ports", List.of()));
        when(neutron.listPortsByNetwork("net-1")).thenReturn(Map.of("ports", List.of()));
        when(neutron.listPortsBySubnet("sub-1")).thenReturn(Map.of("ports", List.of()));

        service.listIps(null, null);
        verify(neutron).listPorts();

        service.listIps("net-1", null);
        verify(neutron).listPortsByNetwork("net-1");

        service.listIps("net-1", "sub-1"); // 서브넷 필터가 우선
        verify(neutron).listPortsBySubnet("sub-1");
    }

    @Test
    void ip_detail_includes_flattened_ips() {
        when(neutron.getPort("port-1"))
                .thenReturn(Map.of("port", samplePort("port-1", "sub-1", "10.0.1.5")));

        Map<String, Object> detail = service.getIpDetail("port-1");

        assertThat(detail)
                .containsEntry("id", "port-1")
                .containsEntry("macAddress", "fa:16:3e:00:00:01");
        assertThat(detail.get("ips")).isInstanceOf(List.class);
        assertThat((List<?>) detail.get("ips")).hasSize(1);
    }

    @Test
    void ip_detail_throws_not_found_when_missing() {
        when(neutron.getPort("nope")).thenReturn(Map.of());

        assertThatThrownBy(() -> service.getIpDetail("nope"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}

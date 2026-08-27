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
import static org.mockito.Mockito.when;

class NetworkZoneServiceTest {

    private NeutronClient neutron;
    private NetworkZoneService service;

    @BeforeEach
    void setUp() {
        neutron = mock(NeutronClient.class);
        service = new NetworkZoneService(neutron);
    }

    @Test
    void list_zones_maps_neutron_networks_to_zone_model() {
        when(neutron.listNetworks()).thenReturn(Map.of("networks", List.of(
                Map.of(
                        "id", "net-1",
                        "name", "private-zone",
                        "status", "ACTIVE",
                        "admin_state_up", true,
                        "shared", false,
                        "router:external", false,
                        "mtu", 1450,
                        "subnets", List.of("sub-1", "sub-2"),
                        "availability_zones", List.of("nova")))));

        List<Map<String, Object>> zones = service.listZones();

        assertThat(zones).hasSize(1);
        Map<String, Object> zone = zones.get(0);
        assertThat(zone)
                .containsEntry("id", "net-1")
                .containsEntry("name", "private-zone")
                .containsEntry("status", "ACTIVE")
                .containsEntry("adminStateUp", true)
                .containsEntry("external", false)
                .containsEntry("subnetCount", 2);
        assertThat(zone.get("subnetIds")).isEqualTo(List.of("sub-1", "sub-2"));
    }

    @Test
    void get_zone_returns_detail() {
        when(neutron.getNetwork("net-1")).thenReturn(Map.of("network", Map.of(
                "id", "net-1",
                "name", "private-zone",
                "status", "ACTIVE",
                "subnets", List.of("sub-1"))));

        Map<String, Object> zone = service.getZone("net-1");

        assertThat(zone)
                .containsEntry("id", "net-1")
                .containsEntry("subnetCount", 1);
    }

    @Test
    void get_zone_throws_not_found_when_missing() {
        when(neutron.getNetwork("nope")).thenReturn(Map.of());

        assertThatThrownBy(() -> service.getZone("nope"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void zone_subnets_tab_maps_snake_case_to_camel_case() {
        when(neutron.listSubnetsByNetwork("net-1")).thenReturn(Map.of("subnets", List.of(
                Map.of(
                        "id", "sub-1",
                        "name", "subnet-a",
                        "cidr", "10.0.1.0/24",
                        "ip_version", 4,
                        "gateway_ip", "10.0.1.1",
                        "enable_dhcp", true,
                        "network_id", "net-1"))));

        List<Map<String, Object>> subnets = service.getZoneSubnets("net-1");

        assertThat(subnets).hasSize(1);
        assertThat(subnets.get(0))
                .containsEntry("id", "sub-1")
                .containsEntry("cidr", "10.0.1.0/24")
                .containsEntry("ipVersion", 4)
                .containsEntry("gatewayIp", "10.0.1.1")
                .containsEntry("enableDhcp", true)
                .containsEntry("networkId", "net-1");
    }

    @Test
    void zone_interfaces_tab_maps_ports() {
        when(neutron.listPortsByNetwork("net-1")).thenReturn(Map.of("ports", List.of(
                Map.of(
                        "id", "port-1",
                        "status", "ACTIVE",
                        "mac_address", "fa:16:3e:aa:bb:cc",
                        "device_owner", "compute:nova",
                        "device_id", "vm-1",
                        "fixed_ips", List.of(Map.of("subnet_id", "sub-1", "ip_address", "10.0.1.5")),
                        "network_id", "net-1"))));

        List<Map<String, Object>> interfaces = service.getZoneInterfaces("net-1");

        assertThat(interfaces).hasSize(1);
        assertThat(interfaces.get(0))
                .containsEntry("id", "port-1")
                .containsEntry("macAddress", "fa:16:3e:aa:bb:cc")
                .containsEntry("deviceOwner", "compute:nova")
                .containsEntry("deviceId", "vm-1");
    }

    @Test
    void empty_neutron_response_returns_empty_list() {
        when(neutron.listNetworks()).thenReturn(Map.of());

        assertThat(service.listZones()).isEmpty();
    }
}

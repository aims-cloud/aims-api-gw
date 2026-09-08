package com.innogrid.aims.openstack.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.openstack.KeystoneClient;
import com.innogrid.aims.openstack.NeutronClient;
import com.innogrid.aims.openstack.NovaClient;
import com.innogrid.aims.openstack.OpenStackProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenStackTopologyServiceTest {

    private KeystoneClient keystone;
    private NeutronClient neutron;
    private NovaClient nova;
    private OpenStackTopologyService service;

    @BeforeEach
    void setUp() {
        OpenStackProperties props = new OpenStackProperties();
        props.setZoneName("openstack-lab");
        props.setProjectName("aims-project");

        keystone = mock(KeystoneClient.class);
        neutron = mock(NeutronClient.class);
        nova = mock(NovaClient.class);
        service = new OpenStackTopologyService(props, keystone, neutron, nova);
    }

    private void stubBaseData() {
        when(keystone.listProjects()).thenReturn(Map.of("projects", List.of(
                Map.of("id", "p-1", "name", "admin"))));
        when(neutron.listNetworks()).thenReturn(Map.of("networks", List.of(
                Map.of("id", "net-1", "name", "internal_only", "project_id", "p-1",
                        "status", "ACTIVE", "admin_state_up", true,
                        "mtu", 1442, "shared", false, "router:external", false))));
        when(neutron.listSubnets()).thenReturn(Map.of("subnets", List.of(
                Map.of("id", "sub-1", "name", "internal_subnet", "network_id", "net-1",
                        "cidr", "192.168.0.0/24", "gateway_ip", "192.168.0.1"))));
        when(neutron.listPorts()).thenReturn(Map.of("ports", List.of(
                Map.of("id", "port-1", "device_owner", "compute:nova", "device_id", "srv-1",
                        "status", "ACTIVE", "network_id", "net-1",
                        "fixed_ips", List.of(Map.of("subnet_id", "sub-1", "ip_address", "192.168.0.10"))),
                Map.of("id", "port-dhcp", "device_owner", "network:dhcp", "device_id", "dhcp-1",
                        "status", "ACTIVE", "network_id", "net-1",
                        "fixed_ips", List.of(Map.of("subnet_id", "sub-1", "ip_address", "192.168.0.2"))))));
        when(nova.listServers()).thenReturn(Map.of("servers", List.of(
                Map.of("id", "srv-1", "name", "tryoo-window-vm", "status", "ACTIVE"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void assembles_project_network_subnet_vm_tree() {
        stubBaseData();

        Map<String, Object> topology = service.getTopology();

        assertThat(topology)
                .containsEntry("deployZone", "openstack-lab")
                .containsEntry("provider", "openstack");

        List<Map<String, Object>> projects = (List<Map<String, Object>>) topology.get("projects");
        assertThat(projects).hasSize(1);
        assertThat(projects.get(0)).containsEntry("name", "admin");

        List<Map<String, Object>> networks = (List<Map<String, Object>>) projects.get(0).get("networks");
        assertThat(networks).hasSize(1);
        assertThat(networks.get(0))
                .containsEntry("name", "internal_only")
                .containsEntry("mtu", 1442)
                .containsEntry("shared", false)
                .containsEntry("status", "ACTIVE");

        List<Map<String, Object>> subnets = (List<Map<String, Object>>) networks.get(0).get("subnets");
        assertThat(subnets).hasSize(1);
        assertThat(subnets.get(0)).containsEntry("cidr", "192.168.0.0/24");

        // compute 포트만 VM으로 잡히고, DHCP 포트는 제외
        List<Map<String, Object>> vms = (List<Map<String, Object>>) subnets.get(0).get("vms");
        assertThat(vms).hasSize(1);
        assertThat(vms.get(0))
                .containsEntry("id", "srv-1")
                .containsEntry("name", "tryoo-window-vm")
                .containsEntry("status", "ACTIVE")
                .containsEntry("ipAddress", "192.168.0.10");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nova_failure_falls_back_to_device_id() {
        stubBaseData();
        when(nova.listServers()).thenThrow(new RuntimeException("nova down"));

        Map<String, Object> topology = service.getTopology();

        List<Map<String, Object>> projects = (List<Map<String, Object>>) topology.get("projects");
        List<Map<String, Object>> networks = (List<Map<String, Object>>) projects.get(0).get("networks");
        List<Map<String, Object>> subnets = (List<Map<String, Object>>) networks.get(0).get("subnets");
        List<Map<String, Object>> vms = (List<Map<String, Object>>) subnets.get(0).get("vms");

        assertThat(vms.get(0)).containsEntry("name", "srv-1"); // device_id 대체 표기
    }

    @Test
    @SuppressWarnings("unchecked")
    void keystone_failure_labels_project_by_id() {
        stubBaseData();
        when(keystone.listProjects()).thenThrow(new RuntimeException("403"));

        Map<String, Object> topology = service.getTopology();

        List<Map<String, Object>> projects = (List<Map<String, Object>>) topology.get("projects");
        assertThat(projects.get(0)).containsEntry("name", "p-1"); // 이름 대신 project_id
    }

    @Test
    void deploy_zones_returns_single_configured_connection() {
        List<Map<String, Object>> zones = service.listDeployZones();

        assertThat(zones).hasSize(1);
        assertThat(zones.get(0))
                .containsEntry("name", "openstack-lab")
                .containsEntry("provider", "openstack")
                .containsEntry("configured", false); // authUrl 등 미설정 상태
    }

    @Test
    @SuppressWarnings("unchecked")
    void network_without_subnets_gets_empty_lists() {
        when(keystone.listProjects()).thenReturn(Map.of("projects", List.of()));
        when(neutron.listNetworks()).thenReturn(Map.of("networks", List.of(
                Map.of("id", "net-2", "name", "empty-net", "project_id", "p-9"))));
        when(neutron.listSubnets()).thenReturn(Map.of());
        when(neutron.listPorts()).thenReturn(Map.of());
        when(nova.listServers()).thenReturn(Map.of());

        Map<String, Object> topology = service.getTopology();

        List<Map<String, Object>> projects = (List<Map<String, Object>>) topology.get("projects");
        List<Map<String, Object>> networks = (List<Map<String, Object>>) projects.get(0).get("networks");
        assertThat((List<?>) networks.get(0).get("subnets")).isEmpty();
    }
}

package com.innogrid.aims.openstack.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.openstack.KeystoneClient;
import com.innogrid.aims.openstack.NeutronClient;
import com.innogrid.aims.openstack.NovaClient;
import com.innogrid.aims.openstack.OpenStackProperties;
import com.innogrid.aims.openstack.dto.TopologyDtos.ComputeZone;
import com.innogrid.aims.openstack.dto.TopologyDtos.DeployZone;
import com.innogrid.aims.openstack.dto.TopologyDtos.NetworkNode;
import com.innogrid.aims.openstack.dto.TopologyDtos.NetworkProject;
import com.innogrid.aims.openstack.dto.TopologyDtos.PortRow;
import com.innogrid.aims.openstack.dto.TopologyDtos.RouterNode;
import com.innogrid.aims.openstack.dto.TopologyDtos.SubnetNode;
import com.innogrid.aims.openstack.dto.TopologyDtos.TopologyTree;

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
                Map.of("id", "p-admin", "name", "admin"),
                Map.of("id", "p-empty", "name", "empty-project"))));

        when(neutron.listNetworks()).thenReturn(Map.of("networks", List.of(
                Map.of("id", "net-ext", "name", "external", "project_id", "p-admin",
                        "status", "ACTIVE", "mtu", 1500, "shared", false, "router:external", true),
                Map.of("id", "net-nat", "name", "nat", "project_id", "p-admin",
                        "status", "ACTIVE", "mtu", 1442, "shared", false, "router:external", false),
                Map.of("id", "net-iso", "name", "internal_only", "project_id", "p-admin",
                        "status", "ACTIVE", "mtu", 1442, "shared", false, "router:external", false))));

        when(neutron.listSubnets()).thenReturn(Map.of("subnets", List.of(
                Map.of("id", "sub-nat", "name", "nat-internal", "network_id", "net-nat",
                        "cidr", "172.16.0.0/16", "gateway_ip", "172.16.0.1"))));

        Map<String, Object> routerWithGateway = new HashMap<>();
        routerWithGateway.put("id", "r-nat");
        routerWithGateway.put("name", "nat-router");
        routerWithGateway.put("status", "ACTIVE");
        routerWithGateway.put("admin_state_up", true);
        routerWithGateway.put("project_id", "p-admin");
        routerWithGateway.put("routes", List.of(Map.of("destination", "0.0.0.0/0")));
        routerWithGateway.put("external_gateway_info", Map.of(
                "network_id", "net-ext",
                "external_fixed_ips", List.of(Map.of("ip_address", "192.168.110.235"))));

        Map<String, Object> routerNoGateway = new HashMap<>();
        routerNoGateway.put("id", "r-none");
        routerNoGateway.put("name", "0808-1");
        routerNoGateway.put("status", "ACTIVE");
        routerNoGateway.put("project_id", "p-admin");
        routerNoGateway.put("external_gateway_info", null);

        when(neutron.listRouters()).thenReturn(Map.of("routers",
                List.of(routerWithGateway, routerNoGateway)));

        when(neutron.listPorts()).thenReturn(Map.of("ports", List.of(
                Map.of("id", "port-rif", "device_owner", "network:router_interface",
                        "device_id", "r-nat", "network_id", "net-nat",
                        "fixed_ips", List.of(Map.of("subnet_id", "sub-nat", "ip_address", "172.16.0.1"))),
                Map.of("id", "port-vm", "device_owner", "compute:nova", "device_id", "srv-1",
                        "status", "ACTIVE", "network_id", "net-nat",
                        "mac_address", "fa:16:3e:aa:bb:cc",
                        "fixed_ips", List.of(Map.of("subnet_id", "sub-nat", "ip_address", "172.16.0.47"))),
                Map.of("id", "port-dhcp", "device_owner", "network:dhcp", "device_id", "dhcp-1",
                        "network_id", "net-nat",
                        "fixed_ips", List.of(Map.of("subnet_id", "sub-nat", "ip_address", "172.16.0.2"))))));

        Map<String, Object> server = new HashMap<>();
        server.put("id", "srv-1");
        server.put("name", "amphora-42a05c88");
        server.put("status", "ACTIVE");
        server.put("tenant_id", "p-admin");
        server.put("OS-EXT-AZ:availability_zone", "nova");
        server.put("OS-EXT-SRV-ATTR:hypervisor_hostname", "com-1");
        when(nova.listServersAllTenants()).thenReturn(Map.of("servers", List.of(server)));

        when(nova.listAvailabilityZones()).thenReturn(Map.of("availabilityZoneInfo", List.of(
                Map.of("zoneName", "nova", "zoneState", Map.of("available", true)))));
        when(nova.listHypervisors()).thenReturn(Map.of("hypervisors", List.of(
                Map.of("hypervisor_hostname", "com-1", "state", "up", "status", "enabled"))));
    }

    private static NetworkProject adminProject(TopologyTree tree) {
        return tree.networkLayer().projects().stream()
                .filter(p -> "admin".equals(p.name())).findFirst().orElseThrow();
    }

    @Test
    void router_with_gateway_carries_ip_and_connected_networks() {
        stubBaseData();

        NetworkProject admin = adminProject(service.getTopology());
        RouterNode natRouter = admin.routers().stream()
                .filter(r -> "nat-router".equals(r.name())).findFirst().orElseThrow();

        assertThat(natRouter.staticRouteCount()).isEqualTo(1);
        assertThat(natRouter.externalGateway().networkName()).isEqualTo("external");
        assertThat(natRouter.externalGateway().ipAddress()).isEqualTo("192.168.110.235");
        assertThat(natRouter.networks()).hasSize(1);
        assertThat(natRouter.networks().get(0).name()).isEqualTo("nat");
    }

    @Test
    void router_without_gateway_goes_to_separate_array() {
        stubBaseData();

        NetworkProject admin = adminProject(service.getTopology());
        assertThat(admin.routers()).extracting(RouterNode::name).containsExactly("nat-router");
        assertThat(admin.routersWithoutGateway()).hasSize(1);
        RouterNode without = admin.routersWithoutGateway().get(0);
        assertThat(without.name()).isEqualTo("0808-1");
        assertThat(without.externalGateway()).isNull();
    }

    @Test
    void groups_unconnected_and_external_networks_separately() {
        stubBaseData();

        NetworkProject admin = adminProject(service.getTopology());
        assertThat(admin.unconnectedNetworks()).extracting(NetworkNode::name).containsExactly("internal_only");
        assertThat(admin.externalNetworks()).hasSize(1);
        assertThat(admin.externalNetworks().get(0).name()).isEqualTo("external");
        assertThat(admin.externalNetworks().get(0).mtu()).isEqualTo(1500);
    }

    @Test
    void subnet_port_rows_have_vm_name_ip_mac_and_exclude_dhcp() {
        stubBaseData();

        NetworkProject admin = adminProject(service.getTopology());
        RouterNode natRouter = admin.routers().stream()
                .filter(r -> "nat-router".equals(r.name())).findFirst().orElseThrow();
        List<SubnetNode> subnets = natRouter.networks().get(0).subnets();
        List<PortRow> ports = subnets.get(0).ports();

        assertThat(ports).hasSize(1);
        assertThat(ports.get(0).vmName()).isEqualTo("amphora-42a05c88");
        assertThat(ports.get(0).ipAddress()).isEqualTo("172.16.0.47");
        assertThat(ports.get(0).macAddress()).isEqualTo("fa:16:3e:aa:bb:cc");
    }

    @Test
    void compute_layer_builds_zone_host_project_vm_chain() {
        stubBaseData();

        ComputeZone zone = service.getTopology().computeLayer().zones().stream()
                .filter(z -> "nova".equals(z.name())).findFirst().orElseThrow();
        assertThat(zone.available()).isTrue();

        var host = zone.hosts().get(0);
        assertThat(host.name()).isEqualTo("com-1");
        assertThat(host.state()).isEqualTo("up");

        var project = host.projects().get(0);
        assertThat(project.name()).isEqualTo("admin");
        assertThat(project.vms().get(0).name()).isEqualTo("amphora-42a05c88");
        assertThat(project.vms().get(0).status()).isEqualTo("ACTIVE");
    }

    @Test
    void projects_without_vm_are_grouped() {
        stubBaseData();

        var withoutVm = service.getTopology().computeLayer().projectsWithoutVm();
        assertThat(withoutVm).extracting(p -> p.name()).containsExactly("empty-project");
    }

    @Test
    void admin_api_failures_degrade_gracefully() {
        stubBaseData();
        when(nova.listServersAllTenants()).thenThrow(new RuntimeException("403"));
        when(nova.listServers()).thenThrow(new RuntimeException("nova down"));
        when(nova.listHypervisors()).thenThrow(new RuntimeException("admin only"));
        when(nova.listAvailabilityZones()).thenThrow(new RuntimeException("down"));
        when(keystone.listProjects()).thenThrow(new RuntimeException("403"));

        TopologyTree tree = service.getTopology();

        assertThat(tree.networkLayer().projects()).isNotEmpty();
        assertThat(tree.networkLayer().projects().get(0).name()).isEqualTo("p-admin");
        assertThat(tree.computeLayer().zones()).isEmpty();
    }

    @Test
    void deploy_zones_returns_single_configured_connection() {
        List<DeployZone> zones = service.listDeployZones();

        assertThat(zones).hasSize(1);
        assertThat(zones.get(0).name()).isEqualTo("openstack-lab");
        assertThat(zones.get(0).provider()).isEqualTo("openstack");
        assertThat(zones.get(0).configured()).isFalse();
    }
}

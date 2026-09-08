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

    /**
     * 탭클라우드잇 캡처 기준 시나리오:
     * - admin 프로젝트: 외부 게이트웨이 있는 라우터(nat-router) → nat 네트워크(서브넷+VM 포트),
     *   게이트웨이 없는 라우터(0808-1), 라우터 미연결 네트워크(internal_only), 외부 네트워크(external)
     * - VM: com-1 호스트(nova 가용영역)에서 admin 프로젝트로 1대
     * - empty-project: VM 없는 프로젝트 그룹
     */
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
                // 라우터 인터페이스: r-nat ↔ net-nat
                Map.of("id", "port-rif", "device_owner", "network:router_interface",
                        "device_id", "r-nat", "network_id", "net-nat",
                        "fixed_ips", List.of(Map.of("subnet_id", "sub-nat", "ip_address", "172.16.0.1"))),
                // VM 포트
                Map.of("id", "port-vm", "device_owner", "compute:nova", "device_id", "srv-1",
                        "status", "ACTIVE", "network_id", "net-nat",
                        "mac_address", "fa:16:3e:aa:bb:cc",
                        "fixed_ips", List.of(Map.of("subnet_id", "sub-nat", "ip_address", "172.16.0.47"))),
                // DHCP 포트 — VM 행에서 제외
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> adminProject(Map<String, Object> topology) {
        Map<String, Object> networkLayer = (Map<String, Object>) topology.get("networkLayer");
        List<Map<String, Object>> projects = (List<Map<String, Object>>) networkLayer.get("projects");
        return projects.stream().filter(p -> "admin".equals(p.get("name"))).findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void router_with_gateway_carries_ip_and_connected_networks() {
        stubBaseData();

        Map<String, Object> admin = adminProject(service.getTopology());
        List<Map<String, Object>> routers = (List<Map<String, Object>>) admin.get("routers");
        Map<String, Object> natRouter = routers.stream()
                .filter(r -> "nat-router".equals(r.get("name"))).findFirst().orElseThrow();

        assertThat(natRouter).containsEntry("staticRouteCount", 1);
        Map<String, Object> gateway = (Map<String, Object>) natRouter.get("externalGateway");
        assertThat(gateway)
                .containsEntry("networkName", "external")
                .containsEntry("ipAddress", "192.168.110.235");

        List<Map<String, Object>> networks = (List<Map<String, Object>>) natRouter.get("networks");
        assertThat(networks).hasSize(1);
        assertThat(networks.get(0)).containsEntry("name", "nat");
    }

    @Test
    @SuppressWarnings("unchecked")
    void router_without_gateway_goes_to_separate_array() {
        stubBaseData();

        Map<String, Object> admin = adminProject(service.getTopology());

        // 게이트웨이 있는 라우터는 routers[], 없는 라우터는 routersWithoutGateway[]
        List<Map<String, Object>> routers = (List<Map<String, Object>>) admin.get("routers");
        assertThat(routers).extracting(r -> r.get("name")).containsExactly("nat-router");

        List<Map<String, Object>> withoutGateway =
                (List<Map<String, Object>>) admin.get("routersWithoutGateway");
        assertThat(withoutGateway).hasSize(1);
        assertThat(withoutGateway.get(0))
                .containsEntry("name", "0808-1");
        assertThat(withoutGateway.get(0).get("externalGateway")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void groups_unconnected_and_external_networks_separately() {
        stubBaseData();

        Map<String, Object> admin = adminProject(service.getTopology());

        List<Map<String, Object>> unconnected =
                (List<Map<String, Object>>) admin.get("unconnectedNetworks");
        assertThat(unconnected).hasSize(1);
        assertThat(unconnected.get(0)).containsEntry("name", "internal_only");

        List<Map<String, Object>> external =
                (List<Map<String, Object>>) admin.get("externalNetworks");
        assertThat(external).hasSize(1);
        assertThat(external.get(0))
                .containsEntry("name", "external")
                .containsEntry("mtu", 1500);
    }

    @Test
    @SuppressWarnings("unchecked")
    void subnet_port_rows_have_vm_name_ip_mac_and_exclude_dhcp() {
        stubBaseData();

        Map<String, Object> admin = adminProject(service.getTopology());
        List<Map<String, Object>> routers = (List<Map<String, Object>>) admin.get("routers");
        Map<String, Object> natRouter = routers.stream()
                .filter(r -> "nat-router".equals(r.get("name"))).findFirst().orElseThrow();
        List<Map<String, Object>> networks = (List<Map<String, Object>>) natRouter.get("networks");
        List<Map<String, Object>> subnets = (List<Map<String, Object>>) networks.get(0).get("subnets");
        List<Map<String, Object>> ports = (List<Map<String, Object>>) subnets.get(0).get("ports");

        assertThat(ports).hasSize(1); // DHCP·라우터 인터페이스 포트 제외
        assertThat(ports.get(0))
                .containsEntry("vmName", "amphora-42a05c88")
                .containsEntry("ipAddress", "172.16.0.47")
                .containsEntry("macAddress", "fa:16:3e:aa:bb:cc");
    }

    @Test
    @SuppressWarnings("unchecked")
    void compute_layer_builds_zone_host_project_vm_chain() {
        stubBaseData();

        Map<String, Object> computeLayer =
                (Map<String, Object>) service.getTopology().get("computeLayer");
        List<Map<String, Object>> zones = (List<Map<String, Object>>) computeLayer.get("zones");

        assertThat(zones).hasSize(1);
        assertThat(zones.get(0))
                .containsEntry("name", "nova")
                .containsEntry("available", true);

        List<Map<String, Object>> hosts = (List<Map<String, Object>>) zones.get(0).get("hosts");
        assertThat(hosts.get(0))
                .containsEntry("name", "com-1")
                .containsEntry("state", "up");

        List<Map<String, Object>> projects = (List<Map<String, Object>>) hosts.get(0).get("projects");
        assertThat(projects.get(0)).containsEntry("name", "admin");

        List<Map<String, Object>> vms = (List<Map<String, Object>>) projects.get(0).get("vms");
        assertThat(vms.get(0))
                .containsEntry("name", "amphora-42a05c88")
                .containsEntry("status", "ACTIVE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void projects_without_vm_are_grouped() {
        stubBaseData();

        Map<String, Object> computeLayer =
                (Map<String, Object>) service.getTopology().get("computeLayer");
        List<Map<String, Object>> withoutVm =
                (List<Map<String, Object>>) computeLayer.get("projectsWithoutVm");

        assertThat(withoutVm).hasSize(1);
        assertThat(withoutVm.get(0)).containsEntry("name", "empty-project");
    }

    @Test
    @SuppressWarnings("unchecked")
    void admin_api_failures_degrade_gracefully() {
        stubBaseData();
        when(nova.listServersAllTenants()).thenThrow(new RuntimeException("403"));
        when(nova.listServers()).thenThrow(new RuntimeException("nova down"));
        when(nova.listHypervisors()).thenThrow(new RuntimeException("admin only"));
        when(nova.listAvailabilityZones()).thenThrow(new RuntimeException("down"));
        when(keystone.listProjects()).thenThrow(new RuntimeException("403"));

        Map<String, Object> topology = service.getTopology();

        // 네트워크 레이어는 유지되고, VM 이름은 device_id로 대체
        Map<String, Object> networkLayer = (Map<String, Object>) topology.get("networkLayer");
        List<Map<String, Object>> projects = (List<Map<String, Object>>) networkLayer.get("projects");
        assertThat(projects).isNotEmpty();
        assertThat(projects.get(0)).containsEntry("name", "p-admin"); // 프로젝트명 대신 id

        // 컴퓨트 레이어는 비어 있지만 구조는 유지
        Map<String, Object> computeLayer = (Map<String, Object>) topology.get("computeLayer");
        assertThat((List<?>) computeLayer.get("zones")).isEmpty();
    }

    @Test
    void deploy_zones_returns_single_configured_connection() {
        List<Map<String, Object>> zones = service.listDeployZones();

        assertThat(zones).hasSize(1);
        assertThat(zones.get(0))
                .containsEntry("name", "openstack-lab")
                .containsEntry("provider", "openstack")
                .containsEntry("configured", false);
    }
}

package com.innogrid.aims.openstack.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.openstack.GlanceClient;
import com.innogrid.aims.openstack.KeystoneClient;
import com.innogrid.aims.openstack.NeutronClient;
import com.innogrid.aims.openstack.NovaClient;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.HypervisorDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.NetworkDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.ProjectDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.RouterDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.ServerDetail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenStackTopologyDetailServiceTest {

    private KeystoneClient keystone;
    private NeutronClient neutron;
    private NovaClient nova;
    private GlanceClient glance;
    private OpenStackTopologyDetailService service;

    @BeforeEach
    void setUp() {
        keystone = mock(KeystoneClient.class);
        neutron = mock(NeutronClient.class);
        nova = mock(NovaClient.class);
        glance = mock(GlanceClient.class);
        service = new OpenStackTopologyDetailService(keystone, neutron, nova, glance);
    }

    @Test
    void router_detail_has_interfaces_and_static_routes() {
        when(neutron.getRouter("r-1")).thenReturn(Map.of("router", Map.of(
                "id", "r-1", "name", "nat-router", "status", "ACTIVE", "admin_state_up", true,
                "distributed", false, "created_at", "2026-06-25T12:52:37Z",
                "routes", List.of(Map.of("destination", "192.168.10.0/24", "nexthop", "10.0.1.3")))));
        when(neutron.listNetworks()).thenReturn(Map.of("networks", List.of(
                Map.of("id", "net-1", "name", "internal"))));
        when(neutron.listSubnets()).thenReturn(Map.of("subnets", List.of(
                Map.of("id", "sub-1", "name", "internal-subnet"))));
        when(neutron.listPortsByDevice("r-1")).thenReturn(Map.of("ports", List.of(
                Map.of("device_owner", "network:router_interface", "network_id", "net-1",
                        "fixed_ips", List.of(Map.of("subnet_id", "sub-1", "ip_address", "10.0.1.1"))))));

        RouterDetail detail = service.getRouterDetail("r-1");

        assertThat(detail.distributed()).isFalse();
        assertThat(detail.createdAt()).isEqualTo("2026-06-25T12:52:37Z");
        assertThat(detail.interfaces()).hasSize(1);
        assertThat(detail.interfaces().get(0).ip()).isEqualTo("10.0.1.1");
        assertThat(detail.interfaces().get(0).networkName()).isEqualTo("internal");
        assertThat(detail.interfaces().get(0).subnetName()).isEqualTo("internal-subnet");
        assertThat(detail.staticRoutes()).hasSize(1);
        assertThat(detail.staticRoutes().get(0).destination()).isEqualTo("192.168.10.0/24");
        assertThat(detail.staticRoutes().get(0).nexthop()).isEqualTo("10.0.1.3");
    }

    @Test
    void router_detail_not_found() {
        when(neutron.getRouter("nope")).thenReturn(Map.of());
        assertThatThrownBy(() -> service.getRouterDetail("nope"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void network_detail_has_type_subnets_with_portcount_and_ports() {
        when(neutron.getNetwork("net-1")).thenReturn(Map.of("network", Map.of(
                "id", "net-1", "name", "internal", "status", "ACTIVE", "admin_state_up", true,
                "provider:network_type", "geneve", "mtu", 1442, "shared", false, "router:external", false)));
        when(neutron.listSubnetsByNetwork("net-1")).thenReturn(Map.of("subnets", List.of(
                Map.of("id", "sub-1", "name", "internal-subnet", "cidr", "10.0.1.0/24",
                        "ip_version", 4, "gateway_ip", "10.0.1.1", "enable_dhcp", true))));
        when(neutron.listPortsByNetwork("net-1")).thenReturn(Map.of("ports", List.of(
                Map.of("device_owner", "compute:nova", "device_id", "srv-1", "status", "ACTIVE",
                        "mac_address", "fa:16:3e:00:00:01", "admin_state_up", true,
                        "fixed_ips", List.of(Map.of("subnet_id", "sub-1", "ip_address", "10.0.1.5"))))));
        when(nova.listServersAllTenants()).thenReturn(Map.of("servers", List.of(
                Map.of("id", "srv-1", "name", "web-01"))));

        NetworkDetail detail = service.getNetworkDetail("net-1");

        assertThat(detail.networkType()).isEqualTo("geneve");
        assertThat(detail.subnets()).hasSize(1);
        assertThat(detail.subnets().get(0).ipVersion()).isEqualTo(4);
        assertThat(detail.subnets().get(0).enableDhcp()).isTrue();
        assertThat(detail.subnets().get(0).portCount()).isEqualTo(1);
        assertThat(detail.ports()).hasSize(1);
        assertThat(detail.ports().get(0).vmName()).isEqualTo("web-01");
        assertThat(detail.ports().get(0).ipAddress()).isEqualTo("10.0.1.5");
    }

    @Test
    void gateway_port_detail_has_ip_status_mac() {
        when(neutron.getPort("port-1")).thenReturn(Map.of("port", Map.of(
                "id", "port-1", "name", "external", "status", "ACTIVE",
                "mac_address", "fa:16:3e:5f:77:6d",
                "fixed_ips", List.of(Map.of("ip_address", "192.168.110.229")))));

        var detail = service.getPortDetail("port-1");
        assertThat(detail.ipAddress()).isEqualTo("192.168.110.229");
        assertThat(detail.status()).isEqualTo("ACTIVE");
        assertThat(detail.macAddress()).isEqualTo("fa:16:3e:5f:77:6d");
    }

    @Test
    void server_detail_has_flavor_image_ips_and_security_rules() {
        when(nova.getServer("srv-1")).thenReturn(Map.of("server", Map.of(
                "id", "srv-1", "name", "amphora-42", "status", "SHUTOFF",
                "OS-EXT-STS:power_state", 4,
                "flavor", Map.of("vcpus", 1, "ram", 1024, "disk", 5, "original_name", "amphora"),
                "image", Map.of("id", "img-1"),
                "addresses", Map.of(
                        "lb-mgmt-net", List.of(Map.of("addr", "10.1.0.233")),
                        "nat", List.of(Map.of("addr", "172.16.2.67"))),
                "security_groups", List.of(Map.of("name", "lb-mgmt-sg")))));
        when(glance.isConfigured()).thenReturn(true);
        when(glance.getImage("img-1")).thenReturn(Map.of("name", "amphora-x64-haproxy.qcow2"));
        when(neutron.listSecurityGroups()).thenReturn(Map.of("security_groups", List.of(
                Map.of("name", "lb-mgmt-sg", "security_group_rules", List.of(
                        Map.of("direction", "ingress", "ethertype", "IPv4", "protocol", "tcp",
                                "port_range_min", 9443, "port_range_max", 9443))))));

        ServerDetail detail = service.getServerDetail("srv-1");

        assertThat(detail.vcpus()).isEqualTo(1);
        assertThat(detail.ramMb()).isEqualTo(1024);
        assertThat(detail.diskGb()).isEqualTo(5);
        assertThat(detail.flavorName()).isEqualTo("amphora");
        assertThat(detail.powerState()).isEqualTo("Shutdown");
        assertThat(detail.imageName()).isEqualTo("amphora-x64-haproxy.qcow2");
        assertThat(detail.ips()).hasSize(2);
        assertThat(detail.securityGroupRules()).hasSize(1);
        assertThat(detail.securityGroupRules().get(0).portRange()).isEqualTo("9443");
        assertThat(detail.securityGroupRules().get(0).protocol()).isEqualTo("tcp");
    }

    @Test
    void server_detail_resolves_flavor_by_id_when_not_embedded() {
        when(nova.getServer("srv-2")).thenReturn(Map.of("server", Map.of(
                "id", "srv-2", "name", "vm2", "status", "ACTIVE",
                "flavor", Map.of("id", "flavor-9"),
                "image", ""))); // image "" = 볼륨 부팅
        when(nova.getFlavor("flavor-9")).thenReturn(Map.of("flavor", Map.of(
                "vcpus", 2, "ram", 2048, "disk", 20, "name", "m1.small")));

        ServerDetail detail = service.getServerDetail("srv-2");
        assertThat(detail.vcpus()).isEqualTo(2);
        assertThat(detail.flavorName()).isEqualTo("m1.small");
        assertThat(detail.imageName()).isNull(); // 볼륨 부팅은 이미지 없음
    }

    @Test
    void hypervisor_detail_has_usage_and_zone() {
        java.util.Map<String, Object> hv = new java.util.HashMap<>();
        hv.put("hypervisor_hostname", "com-1");
        hv.put("host_ip", "192.168.110.101");
        hv.put("hypervisor_type", "QEMU");
        hv.put("state", "up");
        hv.put("status", "enabled");
        hv.put("vcpus", 144);
        hv.put("vcpus_used", 93);
        hv.put("memory_mb", 257600);
        hv.put("memory_mb_used", 239200);
        hv.put("local_gb", 7400);
        hv.put("local_gb_used", 95);
        hv.put("running_vms", 21);
        hv.put("service", Map.of("host", "com-1"));
        when(nova.listHypervisors()).thenReturn(Map.of("hypervisors", List.of(hv)));
        // 1차 경로: 이 호스트에 떠 있는 VM의 AZ
        when(nova.listServersAllTenants()).thenReturn(Map.of("servers", List.of(
                Map.of("OS-EXT-SRV-ATTR:hypervisor_hostname", "com-1",
                        "OS-EXT-AZ:availability_zone", "nova"))));

        HypervisorDetail detail = service.getHypervisorDetail("com-1");

        assertThat(detail.hostIp()).isEqualTo("192.168.110.101");
        assertThat(detail.hypervisorType()).isEqualTo("QEMU");
        assertThat(detail.vcpus()).isEqualTo(144);
        assertThat(detail.vcpusUsed()).isEqualTo(93);
        assertThat(detail.runningVms()).isEqualTo(21);
        assertThat(detail.availabilityZone()).isEqualTo("nova");
    }

    @Test
    void hypervisor_az_falls_back_to_zone_detail_when_no_vm() {
        java.util.Map<String, Object> hv = new java.util.HashMap<>();
        hv.put("hypervisor_hostname", "com-2");
        hv.put("service", Map.of("host", "com-2"));
        when(nova.listHypervisors()).thenReturn(Map.of("hypervisors", List.of(hv)));
        when(nova.listServersAllTenants()).thenReturn(Map.of("servers", List.of())); // VM 없음
        when(nova.listAvailabilityZonesDetail()).thenReturn(Map.of("availabilityZoneInfo", List.of(
                Map.of("zoneName", "nova", "hosts", Map.of("com-2", Map.of())))));

        HypervisorDetail detail = service.getHypervisorDetail("com-2");
        assertThat(detail.availabilityZone()).isEqualTo("nova");
    }

    @Test
    void hypervisor_detail_not_found() {
        when(nova.listHypervisors()).thenReturn(Map.of("hypervisors", List.of()));
        assertThatThrownBy(() -> service.getHypervisorDetail("nope"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void project_detail_has_domain_and_vm_list() {
        when(keystone.getProject("p-1")).thenReturn(Map.of("project", Map.of(
                "id", "p-1", "name", "service", "enabled", true,
                "domain_id", "default", "created_at", "2026-06-25T12:52:43Z")));
        when(nova.listServersAllTenants()).thenReturn(Map.of("servers", List.of(
                Map.of("id", "s1", "name", "amphora-2d1c", "status", "SHUTOFF", "tenant_id", "p-1",
                        "flavor", Map.of("vcpus", 1, "ram", 1024, "disk", 5),
                        "addresses", Map.of("lb-mgmt-net", List.of(Map.of("addr", "10.1.0.14")))),
                Map.of("id", "s2", "name", "other", "status", "ACTIVE", "tenant_id", "p-2"))));

        ProjectDetail detail = service.getProjectDetail("p-1");

        assertThat(detail.domain()).isEqualTo("default");
        assertThat(detail.totalVms()).isEqualTo(1); // p-2 VM 제외
        assertThat(detail.runningVms()).isEqualTo(0); // SHUTOFF
        assertThat(detail.vms().get(0).name()).isEqualTo("amphora-2d1c");
        assertThat(detail.vms().get(0).vcpus()).isEqualTo(1);
        assertThat(detail.vms().get(0).ips().get(0).ipAddress()).isEqualTo("10.1.0.14");
    }

    @Test
    void project_detail_not_found() {
        when(keystone.getProject("nope")).thenReturn(Map.of());
        assertThatThrownBy(() -> service.getProjectDetail("nope"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}

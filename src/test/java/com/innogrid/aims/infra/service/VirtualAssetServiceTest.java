package com.innogrid.aims.infra.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.cloudbarista.tumblebug.TumblebugClient;
import com.innogrid.aims.infra.dto.VirtualAssetDtos.HostSummary;
import com.innogrid.aims.infra.dto.VirtualAssetDtos.HostVm;
import com.innogrid.aims.openstack.NovaClient;
import com.innogrid.aims.openstack.OpenStackProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VirtualAssetServiceTest {

    private OpenStackProperties props;
    private NovaClient nova;
    private TumblebugClient tumblebug;
    private VirtualAssetService service;

    @BeforeEach
    void setUp() {
        props = new OpenStackProperties();
        props.setZoneName("rnd openstack");
        nova = mock(NovaClient.class);
        tumblebug = mock(TumblebugClient.class);
        service = new VirtualAssetService(props, nova, tumblebug);
    }

    /** Tumblebug node(=VM) 한 개를 담은 infra 응답을 만든다. */
    private static Map<String, Object> infraWithNode(String connectionName, String provider,
                                                     String id, String name, String status,
                                                     String publicIp) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("name", name);
        node.put("status", status);
        node.put("connectionName", connectionName);
        node.put("connectionConfig", Map.of("providerName", provider));
        if (publicIp != null) node.put("publicIP", publicIp);
        return Map.of("infra", List.of(Map.of("id", "mci-1", "node", List.of(node))));
    }

    @Test
    void host_summary_maps_hypervisor_usage_and_nulls_vcenter_fields() {
        Map<String, Object> hv = new HashMap<>();
        hv.put("hypervisor_hostname", "rnd-all02");
        hv.put("vcpus", 144);
        hv.put("vcpus_used", 93);
        hv.put("memory_mb", 257600);
        hv.put("memory_mb_used", 239200);
        hv.put("running_vms", 21);
        when(nova.listHypervisors()).thenReturn(Map.of("hypervisors", List.of(hv)));

        List<HostSummary> hosts = service.listHosts();

        assertThat(hosts).hasSize(1);
        HostSummary h = hosts.get(0);
        assertThat(h.deployZone()).isEqualTo("rnd openstack");
        assertThat(h.provider()).isEqualTo("openstack");
        assertThat(h.hostName()).isEqualTo("rnd-all02");
        assertThat(h.vcore()).isEqualTo(144);
        assertThat(h.allocatedCore()).isEqualTo(93);
        assertThat(h.vmCount()).isEqualTo(21);
        assertThat(h.memoryTotalMb()).isEqualTo(257600);
        assertThat(h.memoryUsedMb()).isEqualTo(239200);
        assertThat(h.memoryFreeMb()).isEqualTo(18400);
        // vCenter 전용 필드는 null
        assertThat(h.serialNumber()).isNull();
        assertThat(h.datastoreCount()).isNull();
        assertThat(h.alias()).isNull();
    }

    @Test
    void host_vms_filtered_by_hypervisor_with_status_and_ips() {
        Map<String, Object> vm1 = new HashMap<>();
        vm1.put("id", "srv-1");
        vm1.put("name", "web-01");
        vm1.put("status", "ACTIVE");
        vm1.put("OS-EXT-SRV-ATTR:hypervisor_hostname", "rnd-all02");
        vm1.put("addresses", Map.of("internal", List.of(Map.of("addr", "10.0.0.10"))));
        Map<String, Object> vm2 = new HashMap<>();
        vm2.put("id", "srv-2");
        vm2.put("name", "other");
        vm2.put("OS-EXT-SRV-ATTR:hypervisor_hostname", "rnd-com01"); // 다른 호스트
        when(nova.listServersAllTenants()).thenReturn(Map.of("servers", List.of(vm1, vm2)));

        List<HostVm> vms = service.listHostVms("rnd-all02");

        assertThat(vms).hasSize(1);
        assertThat(vms.get(0).vmName()).isEqualTo("web-01");
        assertThat(vms.get(0).status()).isEqualTo("ACTIVE");
        assertThat(vms.get(0).ipAddresses()).containsExactly("10.0.0.10");
        assertThat(vms.get(0).cluster()).isNull();
        assertThat(vms.get(0).datastores()).isNull();
    }

    @Test
    void openstack_failure_returns_empty() {
        when(nova.listHypervisors()).thenThrow(new RuntimeException("down"));
        assertThat(service.listHosts()).isEmpty();
    }

    @Test
    void public_cloud_host_grouped_by_connection_with_null_usage() {
        when(nova.listHypervisors()).thenReturn(Map.of());   // OpenStack 없음
        when(tumblebug.listNamespaces()).thenReturn(Map.of("ns", List.of(Map.of("id", "default"))));
        when(tumblebug.listInfra("default")).thenReturn(
                infraWithNode("aws-ap-northeast-2", "aws", "vm-1", "web-seoul", "Running", "3.35.1.1"));

        List<HostSummary> hosts = service.listHosts();

        assertThat(hosts).hasSize(1);
        HostSummary h = hosts.get(0);
        assertThat(h.provider()).isEqualTo("aws");
        assertThat(h.hostId()).isEqualTo("aws-ap-northeast-2");
        assertThat(h.deployZone()).isEqualTo("ap-northeast-2");   // connection 에서 provider 접두어 제거
        assertThat(h.vmCount()).isEqualTo(1);
        // 공개 클라우드는 하이퍼바이저 사용률 제공 불가 → null
        assertThat(h.vcore()).isNull();
        assertThat(h.allocatedCore()).isNull();
        assertThat(h.memoryTotalMb()).isNull();
    }

    @Test
    void public_cloud_vms_listed_by_connection() {
        when(tumblebug.listNamespaces()).thenReturn(Map.of("ns", List.of(Map.of("id", "default"))));
        when(tumblebug.listInfra("default")).thenReturn(
                infraWithNode("azure-koreacentral", "azure", "vm-9", "app-01", "Running", "20.1.2.3"));

        List<HostVm> vms = service.listHostVms("azure-koreacentral");

        assertThat(vms).hasSize(1);
        assertThat(vms.get(0).vmId()).isEqualTo("vm-9");
        assertThat(vms.get(0).vmName()).isEqualTo("app-01");
        assertThat(vms.get(0).status()).isEqualTo("Running");
        assertThat(vms.get(0).ipAddresses()).containsExactly("20.1.2.3");
    }

    @Test
    void openstack_nodes_excluded_from_tumblebug_source() {
        when(nova.listHypervisors()).thenReturn(Map.of());
        when(tumblebug.listNamespaces()).thenReturn(Map.of("ns", List.of(Map.of("id", "openstack"))));
        when(tumblebug.listInfra("openstack")).thenReturn(
                infraWithNode("openstack-regionone", "openstack", "vm-os", "os-vm", "Running", "192.168.0.5"));

        // OpenStack provider 는 Nova 가 담당하므로 Tumblebug 소스에서 제외 → 호스트 0
        assertThat(service.listHosts()).isEmpty();
    }
}

package com.innogrid.aims.infra.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private VirtualAssetService service;

    @BeforeEach
    void setUp() {
        props = new OpenStackProperties();
        props.setZoneName("rnd openstack");
        nova = mock(NovaClient.class);
        service = new VirtualAssetService(props, nova);
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
}

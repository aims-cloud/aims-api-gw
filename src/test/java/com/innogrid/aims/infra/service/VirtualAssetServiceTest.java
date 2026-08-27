package com.innogrid.aims.infra.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.cloudbarista.spider.SpiderClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VirtualAssetServiceTest {

    private SpiderClient spiderClient;
    private VirtualAssetService service;

    @BeforeEach
    void setUp() {
        spiderClient = mock(SpiderClient.class);
        service = new VirtualAssetService(spiderClient);
    }

    @Test
    void list_hosts_maps_connections_with_vm_counts() {
        when(spiderClient.listConnectionConfigs()).thenReturn(Map.of("connectionconfig", List.of(
                Map.of("ConfigName", "aws-seoul", "ProviderName", "AWS", "RegionName", "aws-seoul-region",
                        "DriverName", "aws-driver"),
                Map.of("ConfigName", "gcp-tokyo", "ProviderName", "GCP", "RegionName", "gcp-tokyo-region",
                        "DriverName", "gcp-driver"))));
        when(spiderClient.listVMs("aws-seoul")).thenReturn(Map.of("vm", List.of(
                Map.of("VMSpecName", "t3.medium"), Map.of("VMSpecName", "t3.large"))));
        when(spiderClient.listVMs("gcp-tokyo")).thenReturn(Map.of("vm", List.of()));

        List<Map<String, Object>> hosts = service.listHosts();

        assertThat(hosts).hasSize(2);
        assertThat(hosts.get(0))
                .containsEntry("connectionName", "aws-seoul")
                .containsEntry("providerName", "AWS")
                .containsEntry("vmCount", 2);
        assertThat(hosts.get(1)).containsEntry("vmCount", 0);
    }

    @Test
    void list_hosts_marks_null_count_on_connection_error() {
        when(spiderClient.listConnectionConfigs()).thenReturn(Map.of("connectionconfig", List.of(
                Map.of("ConfigName", "broken-conn", "ProviderName", "AWS"))));
        when(spiderClient.listVMs("broken-conn")).thenThrow(new RuntimeException("timeout"));

        List<Map<String, Object>> hosts = service.listHosts();

        assertThat(hosts).hasSize(1);
        assertThat(hosts.get(0).get("vmCount")).isNull();
    }

    @Test
    void host_vms_maps_spider_vm_fields() {
        when(spiderClient.listVMs("aws-seoul")).thenReturn(Map.of("vm", List.of(Map.of(
                "IId", Map.of("NameId", "web-01"),
                "VMSpecName", "t3.medium",
                "ImageIId", Map.of("NameId", "ubuntu-22.04"),
                "VpcIID", Map.of("NameId", "vpc-1"),
                "SubnetIID", Map.of("NameId", "subnet-1"),
                "PublicIP", "3.35.1.2",
                "PrivateIP", "10.0.1.5",
                "Region", Map.of("Region", "ap-northeast-2", "Zone", "ap-northeast-2a")))));

        List<Map<String, Object>> vms = service.listHostVms("aws-seoul");

        assertThat(vms).hasSize(1);
        assertThat(vms.get(0))
                .containsEntry("connectionName", "aws-seoul")
                .containsEntry("vmName", "web-01")
                .containsEntry("vmSpecName", "t3.medium")
                .containsEntry("imageName", "ubuntu-22.04")
                .containsEntry("vpcName", "vpc-1")
                .containsEntry("publicIp", "3.35.1.2")
                .containsEntry("region", "ap-northeast-2")
                .containsEntry("zone", "ap-northeast-2a");
    }

    @Test
    void empty_spider_responses_return_empty_lists() {
        when(spiderClient.listConnectionConfigs()).thenReturn(Map.of());
        assertThat(service.listHosts()).isEmpty();

        when(spiderClient.listVMs("none")).thenReturn(Map.of());
        assertThat(service.listHostVms("none")).isEmpty();
    }
}

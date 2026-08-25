package com.innogrid.aims.cloudbarista.spider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpiderRequestConverterTest {

    @Test
    void vpc_create_request_maps_to_spider_format() {
        Map<String, Object> subnet = new LinkedHashMap<>();
        subnet.put("name", "test-subnet-01");
        subnet.put("ipv4Cidr", "10.0.1.0/24");

        Map<String, Object> reqInfo = new LinkedHashMap<>();
        reqInfo.put("name", "test-vpc-01");
        reqInfo.put("ipv4Cidr", "10.0.0.0/16");
        reqInfo.put("subnetInfoList", List.of(subnet));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("connectionName", "aws-seoul");
        request.put("reqInfo", reqInfo);

        Map<String, Object> result = SpiderRequestConverter.toSpider(request);

        assertThat(result).containsKey("ConnectionName");
        assertThat(result.get("ConnectionName")).isEqualTo("aws-seoul");

        @SuppressWarnings("unchecked")
        Map<String, Object> spiderReqInfo = (Map<String, Object>) result.get("ReqInfo");
        assertThat(spiderReqInfo).containsEntry("Name", "test-vpc-01");
        assertThat(spiderReqInfo).containsEntry("IPv4_CIDR", "10.0.0.0/16");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subnets =
                (List<Map<String, Object>>) spiderReqInfo.get("SubnetInfoList");
        assertThat(subnets).hasSize(1);
        assertThat(subnets.get(0)).containsEntry("Name", "test-subnet-01");
        assertThat(subnets.get(0)).containsEntry("IPv4_CIDR", "10.0.1.0/24");
    }

    @Test
    void security_group_create_request_maps_correctly() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("fromPort", "22");
        rule.put("toPort", "22");
        rule.put("ipProtocol", "tcp");
        rule.put("direction", "inbound");
        rule.put("cidr", "0.0.0.0/0");

        Map<String, Object> reqInfo = new LinkedHashMap<>();
        reqInfo.put("name", "test-sg");
        reqInfo.put("vpcName", "test-vpc");
        reqInfo.put("securityRules", List.of(rule));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("connectionName", "aws-seoul");
        request.put("reqInfo", reqInfo);

        Map<String, Object> result = SpiderRequestConverter.toSpider(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> spiderReqInfo = (Map<String, Object>) result.get("ReqInfo");
        assertThat(spiderReqInfo).containsEntry("Name", "test-sg");
        assertThat(spiderReqInfo).containsEntry("VPCName", "test-vpc");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules =
                (List<Map<String, Object>>) spiderReqInfo.get("SecurityRules");
        assertThat(rules.get(0))
                .containsEntry("FromPort", "22")
                .containsEntry("ToPort", "22")
                .containsEntry("IPProtocol", "tcp")
                .containsEntry("Direction", "inbound")
                .containsEntry("CIDR", "0.0.0.0/0");
    }

    @Test
    void vm_create_request_maps_correctly() {
        Map<String, Object> reqInfo = new LinkedHashMap<>();
        reqInfo.put("name", "test-vm");
        reqInfo.put("imageName", "ubuntu-22.04");
        reqInfo.put("vpcName", "test-vpc");
        reqInfo.put("subnetName", "test-subnet");
        reqInfo.put("securityGroupNames", List.of("test-sg"));
        reqInfo.put("vmSpecName", "t3.medium");
        reqInfo.put("keyPairName", "test-key");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("connectionName", "aws-seoul");
        request.put("reqInfo", reqInfo);

        Map<String, Object> result = SpiderRequestConverter.toSpider(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> spiderReqInfo = (Map<String, Object>) result.get("ReqInfo");
        assertThat(spiderReqInfo)
                .containsEntry("Name", "test-vm")
                .containsEntry("ImageName", "ubuntu-22.04")
                .containsEntry("VPCName", "test-vpc")
                .containsEntry("SubnetName", "test-subnet")
                .containsEntry("VMSpecName", "t3.medium")
                .containsEntry("KeyPairName", "test-key");
        assertThat(spiderReqInfo.get("SecurityGroupNames")).isEqualTo(List.of("test-sg"));
    }

    @Test
    void disk_create_request_maps_correctly() {
        Map<String, Object> reqInfo = new LinkedHashMap<>();
        reqInfo.put("name", "test-disk");
        reqInfo.put("diskType", "gp3");
        reqInfo.put("diskSize", "100");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("connectionName", "aws-seoul");
        request.put("reqInfo", reqInfo);

        Map<String, Object> result = SpiderRequestConverter.toSpider(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> spiderReqInfo = (Map<String, Object>) result.get("ReqInfo");
        assertThat(spiderReqInfo)
                .containsEntry("Name", "test-disk")
                .containsEntry("DiskType", "gp3")
                .containsEntry("DiskSize", "100");
    }

    @Test
    void snapshot_create_request_maps_correctly() {
        Map<String, Object> reqInfo = new LinkedHashMap<>();
        reqInfo.put("name", "test-snapshot");
        reqInfo.put("sourceVM", "test-vm");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("connectionName", "aws-seoul");
        request.put("reqInfo", reqInfo);

        Map<String, Object> result = SpiderRequestConverter.toSpider(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> spiderReqInfo = (Map<String, Object>) result.get("ReqInfo");
        assertThat(spiderReqInfo)
                .containsEntry("Name", "test-snapshot")
                .containsEntry("SourceVM", "test-vm");
    }

    @Test
    void unknown_keys_are_passed_through() {
        Map<String, Object> request = Map.of(
                "connectionName", "aws-seoul",
                "customField", "value123"
        );

        Map<String, Object> result = SpiderRequestConverter.toSpider(request);

        assertThat(result).containsEntry("ConnectionName", "aws-seoul");
        assertThat(result).containsEntry("customField", "value123");
    }

    @Test
    void null_input_returns_null() {
        assertThat(SpiderRequestConverter.toSpider(null)).isNull();
    }
}

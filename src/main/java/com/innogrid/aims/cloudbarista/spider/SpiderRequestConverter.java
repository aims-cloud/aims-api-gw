package com.innogrid.aims.cloudbarista.spider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AIMS camelCase 필드명을 CB-Spider가 기대하는 필드명으로 변환한다.
 * CB-Spider REST API는 PascalCase / 언더스코어 혼합 필드명을 사용한다.
 */
public final class SpiderRequestConverter {

    private static final Map<String, String> FIELD_MAP = Map.ofEntries(
            // 공통
            Map.entry("connectionName", "ConnectionName"),
            Map.entry("reqInfo", "ReqInfo"),
            Map.entry("name", "Name"),

            // VPC / 서브넷
            Map.entry("ipv4Cidr", "IPv4_CIDR"),
            Map.entry("subnetInfoList", "SubnetInfoList"),

            // 보안그룹
            Map.entry("vpcName", "VPCName"),
            Map.entry("securityRules", "SecurityRules"),
            Map.entry("fromPort", "FromPort"),
            Map.entry("toPort", "ToPort"),
            Map.entry("ipProtocol", "IPProtocol"),
            Map.entry("direction", "Direction"),
            Map.entry("cidr", "CIDR"),
            Map.entry("rules", "RuleInfoList"),

            // VM
            Map.entry("imageName", "ImageName"),
            Map.entry("subnetName", "SubnetName"),
            Map.entry("securityGroupNames", "SecurityGroupNames"),
            Map.entry("vmSpecName", "VMSpecName"),
            Map.entry("keyPairName", "KeyPairName"),
            Map.entry("vmName", "VMName"),
            Map.entry("rootDiskType", "RootDiskType"),
            Map.entry("rootDiskSize", "RootDiskSize"),

            // 디스크
            Map.entry("diskType", "DiskType"),
            Map.entry("diskSize", "DiskSize"),
            Map.entry("size", "Size"),

            // 스냅샷
            Map.entry("sourceVM", "SourceVM")
    );

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toSpider(Map<String, Object> src) {
        if (src == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : src.entrySet()) {
            String key = FIELD_MAP.getOrDefault(entry.getKey(), entry.getKey());
            Object val = entry.getValue();
            if (val instanceof Map) {
                val = toSpider((Map<String, Object>) val);
            } else if (val instanceof List<?> list) {
                val = list.stream()
                        .map(item -> item instanceof Map
                                ? toSpider((Map<String, Object>) item) : item)
                        .toList();
            }
            result.put(key, val);
        }
        return result;
    }

    private SpiderRequestConverter() {}
}

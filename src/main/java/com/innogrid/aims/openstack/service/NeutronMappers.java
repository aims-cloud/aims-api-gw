package com.innogrid.aims.openstack.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Neutron 응답(snake_case)을 화면 모델(camelCase)로 변환하는 공용 매퍼.
 */
final class NeutronMappers {

    private NeutronMappers() {}

    static Map<String, Object> toZoneSummary(Map<String, Object> network) {
        Map<String, Object> zone = new LinkedHashMap<>();
        zone.put("id", network.get("id"));
        zone.put("name", network.get("name"));
        zone.put("status", network.get("status"));
        zone.put("adminStateUp", network.get("admin_state_up"));
        zone.put("shared", network.get("shared"));
        zone.put("external", network.get("router:external"));
        zone.put("mtu", network.get("mtu"));
        zone.put("availabilityZones", network.get("availability_zones"));
        List<Object> subnetIds = castList(network.get("subnets"));
        zone.put("subnetIds", subnetIds);
        zone.put("subnetCount", subnetIds.size());
        zone.put("createdAt", network.get("created_at"));
        return zone;
    }

    static Map<String, Object> toSubnetSummary(Map<String, Object> subnet) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", subnet.get("id"));
        result.put("name", subnet.get("name"));
        result.put("cidr", subnet.get("cidr"));
        result.put("ipVersion", subnet.get("ip_version"));
        result.put("gatewayIp", subnet.get("gateway_ip"));
        result.put("enableDhcp", subnet.get("enable_dhcp"));
        result.put("allocationPools", subnet.get("allocation_pools"));
        result.put("networkId", subnet.get("network_id"));
        result.put("createdAt", subnet.get("created_at"));
        return result;
    }

    static Map<String, Object> toInterfaceSummary(Map<String, Object> port) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", port.get("id"));
        result.put("name", port.get("name"));
        result.put("status", port.get("status"));
        result.put("macAddress", port.get("mac_address"));
        result.put("deviceOwner", port.get("device_owner"));
        result.put("deviceId", port.get("device_id"));
        result.put("fixedIps", port.get("fixed_ips"));
        result.put("networkId", port.get("network_id"));
        return result;
    }

    /**
     * 포트 1개를 IP주소 행(들)으로 펼친다. 포트가 여러 IP를 가지면 IP마다 한 행.
     * subnetId가 주어지면 해당 서브넷의 IP만 남긴다.
     */
    static List<Map<String, Object>> toIpRows(Map<String, Object> port, String subnetId) {
        List<Object> fixedIps = castList(port.get("fixed_ips"));
        if (fixedIps.isEmpty()) return Collections.emptyList();

        List<Map<String, Object>> rows = new java.util.ArrayList<>(fixedIps.size());
        for (Object entry : fixedIps) {
            if (!(entry instanceof Map<?, ?> fixedIp)) continue;
            Object entrySubnetId = fixedIp.get("subnet_id");
            if (subnetId != null && !subnetId.equals(entrySubnetId)) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ipAddress", fixedIp.get("ip_address"));
            row.put("subnetId", entrySubnetId);
            row.put("networkId", port.get("network_id"));
            row.put("portId", port.get("id"));
            row.put("macAddress", port.get("mac_address"));
            row.put("status", port.get("status"));
            row.put("deviceOwner", port.get("device_owner"));
            row.put("deviceId", port.get("device_id"));
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> extractList(Map<String, Object> response, String key) {
        if (response == null) return Collections.emptyList();
        Object value = response.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> extractMap(Map<String, Object> response, String key) {
        if (response == null) return Collections.emptyMap();
        Object value = response.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    static List<Object> castList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return Collections.emptyList();
    }
}

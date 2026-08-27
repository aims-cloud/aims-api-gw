package com.innogrid.aims.openstack.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.openstack.NeutronClient;

/**
 * 네트워크 구역 서비스.
 * OpenStack Neutron 네트워크를 화면의 '네트워크 구역' 모델로 매핑하여 표출한다.
 * 1~3세부가 모두 OpenStack을 사용하므로 Neutron이 단일 원본이다 (조회 전용).
 */
@Service
public class NetworkZoneService {

    private final NeutronClient neutron;

    public NetworkZoneService(NeutronClient neutron) {
        this.neutron = neutron;
    }

    /** 구역 목록 — Neutron 네트워크 목록을 구역 요약으로 변환. */
    public List<Map<String, Object>> listZones() {
        List<Map<String, Object>> networks = extractList(neutron.listNetworks(), "networks");
        List<Map<String, Object>> zones = new ArrayList<>(networks.size());
        for (Map<String, Object> network : networks) {
            zones.add(toZoneSummary(network));
        }
        return zones;
    }

    /** 구역 상세 — 단일 Neutron 네트워크. */
    public Map<String, Object> getZone(String networkId) {
        Map<String, Object> response = neutron.getNetwork(networkId);
        Map<String, Object> network = extractMap(response, "network");
        if (network.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "네트워크 구역을 찾을 수 없습니다: " + networkId);
        }
        return toZoneSummary(network);
    }

    /** 구역 상세 — 서브넷 탭. */
    public List<Map<String, Object>> getZoneSubnets(String networkId) {
        List<Map<String, Object>> subnets =
                extractList(neutron.listSubnetsByNetwork(networkId), "subnets");
        List<Map<String, Object>> result = new ArrayList<>(subnets.size());
        for (Map<String, Object> subnet : subnets) {
            result.add(toSubnetSummary(subnet));
        }
        return result;
    }

    /** 구역 상세 — 네트워크 인터페이스(포트) 탭. */
    public List<Map<String, Object>> getZoneInterfaces(String networkId) {
        List<Map<String, Object>> ports =
                extractList(neutron.listPortsByNetwork(networkId), "ports");
        List<Map<String, Object>> result = new ArrayList<>(ports.size());
        for (Map<String, Object> port : ports) {
            result.add(toInterfaceSummary(port));
        }
        return result;
    }

    // ── Neutron 응답 → 화면 모델 매핑 ───────────────────────────

    private static Map<String, Object> toZoneSummary(Map<String, Object> network) {
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

    private static Map<String, Object> toSubnetSummary(Map<String, Object> subnet) {
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

    private static Map<String, Object> toInterfaceSummary(Map<String, Object> port) {
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

    // ── 응답 파싱 헬퍼 ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractList(Map<String, Object> response, String key) {
        if (response == null) return Collections.emptyList();
        Object value = response.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractMap(Map<String, Object> response, String key) {
        if (response == null) return Collections.emptyMap();
        Object value = response.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return Collections.emptyList();
    }
}

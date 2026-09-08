package com.innogrid.aims.openstack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * OpenStack 네트워크 카탈로그 (IPAM용).
 * - 인터페이스 관리 후보: Neutron 네트워크 목록 (배포영역/네트워크명)
 * - IP 동기화: Neutron 포트의 fixed_ips 를 할당 IP로 변환 (자원/MAC/포트그룹)
 */
@Component
public class OpenStackNetworkCatalog {

    public record NetworkOption(String deployZone, String networkName, String openstackNetworkId) {}

    public record AllocatedIp(String ip, String macAddress, String resourceType,
                              String resourceId, String resourceName, String portGroup) {}

    private final OpenStackProperties props;
    private final NeutronClient neutron;
    private final NovaClient nova;

    public OpenStackNetworkCatalog(OpenStackProperties props, NeutronClient neutron, NovaClient nova) {
        this.props = props;
        this.neutron = neutron;
        this.nova = nova;
    }

    /** 인터페이스 관리 후보 — OpenStack 네트워크 목록. */
    public List<NetworkOption> listNetworks() {
        List<NetworkOption> result = new ArrayList<>();
        for (Map<String, Object> network : list(neutron.listNetworks(), "networks")) {
            result.add(new NetworkOption(props.getZoneName(),
                    string(network.get("name")), string(network.get("id"))));
        }
        return result;
    }

    /** 전체 할당 IP — Neutron 포트의 fixed_ips 를 IP 단위로 펼침. */
    public List<AllocatedIp> listAllocatedIps() {
        Map<String, String> networkNameById = new HashMap<>();
        for (Map<String, Object> network : list(neutron.listNetworks(), "networks")) {
            networkNameById.put(string(network.get("id")), string(network.get("name")));
        }
        Map<String, String> serverNameById = serverNames();

        List<AllocatedIp> result = new ArrayList<>();
        for (Map<String, Object> port : list(neutron.listPorts(), "ports")) {
            String owner = string(port.get("device_owner"));
            boolean isInstance = owner != null && owner.startsWith("compute");
            String deviceId = string(port.get("device_id"));
            String mac = string(port.get("mac_address"));
            String portGroup = networkNameById.get(string(port.get("network_id")));
            String resourceType = isInstance ? "INSTANCE" : (owner == null ? null : owner);
            String resourceName = isInstance ? serverNameById.getOrDefault(deviceId, deviceId) : null;

            for (Object entry : castList(port.get("fixed_ips"))) {
                if (!(entry instanceof Map<?, ?> fixedIp)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> ipMap = (Map<String, Object>) fixedIp;
                result.add(new AllocatedIp(string(ipMap.get("ip_address")), mac,
                        resourceType, isInstance ? deviceId : null, resourceName, portGroup));
            }
        }
        return result;
    }

    private Map<String, String> serverNames() {
        Map<String, String> names = new HashMap<>();
        try {
            for (Map<String, Object> server : list(nova.listServersAllTenants(), "servers")) {
                names.put(string(server.get("id")), string(server.get("name")));
            }
        } catch (Exception ignored) {}
        return names;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> response, String key) {
        if (response != null && response.get(key) instanceof List<?> l) {
            return (List<Map<String, Object>>) l;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : Collections.emptyList();
    }

    private static String string(Object v) { return v == null ? null : v.toString(); }
}

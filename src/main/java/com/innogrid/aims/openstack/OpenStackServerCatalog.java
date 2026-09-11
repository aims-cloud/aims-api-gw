package com.innogrid.aims.openstack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * OpenStack 서버 카탈로그.
 * 가상서버 그룹의 VM 후보 목록·상태·표시정보(호스트/IP)를 Nova에서 조회해 단순 레코드로 제공한다.
 * 배포영역은 현재 단일 OpenStack 연결(설정된 zone-name) 기준.
 */
@Component
public class OpenStackServerCatalog {

    /** 가상서버 그룹에서 다루는 서버 요약. */
    public record ServerInfo(String serverId, String deployZone, String vmName,
                             String host, String ip, String status) {}

    private final OpenStackProperties props;
    private final NovaClient nova;

    public OpenStackServerCatalog(OpenStackProperties props, NovaClient nova) {
        this.props = props;
        this.nova = nova;
    }

    /** 전체 서버 목록 (후보 조회용). */
    public List<ServerInfo> listServers() {
        List<ServerInfo> result = new ArrayList<>();
        for (Map<String, Object> server : servers()) {
            result.add(toInfo(server));
        }
        return result;
    }

    /** 서버 단건. 없으면 null. */
    public ServerInfo getServer(String serverId) {
        for (Map<String, Object> server : servers()) {
            if (serverId.equals(string(server.get("id")))) {
                return toInfo(server);
            }
        }
        return null;
    }

    /** 서버 id → 상태 맵 (멤버 목록의 실시간 상태 일괄 조회용). */
    public Map<String, String> statusByServerId() {
        Map<String, String> map = new java.util.HashMap<>();
        for (Map<String, Object> server : servers()) {
            map.put(string(server.get("id")), string(server.get("status")));
        }
        return map;
    }

    private ServerInfo toInfo(Map<String, Object> server) {
        return new ServerInfo(string(server.get("id")), props.getZoneName(),
                string(server.get("name")), string(server.get("OS-EXT-SRV-ATTR:hypervisor_hostname")),
                firstIp(server), string(server.get("status")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> servers() {
        Object response;
        try {
            response = nova.listServersAllTenants();
        } catch (Exception e) {
            try {
                response = nova.listServers();
            } catch (Exception ex) {
                return Collections.emptyList();
            }
        }
        if (response instanceof Map<?, ?> map && map.get("servers") instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static String firstIp(Map<String, Object> server) {
        Object addresses = server.get("addresses");
        if (addresses instanceof Map<?, ?> addrMap) {
            for (Object addrList : addrMap.values()) {
                if (addrList instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof Map<?, ?> first) {
                    return string(((Map<String, Object>) first).get("addr"));
                }
            }
        }
        return null;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}

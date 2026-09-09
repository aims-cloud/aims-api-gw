package com.innogrid.aims.infra.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.innogrid.aims.infra.dto.VirtualAssetDtos.HostSummary;
import com.innogrid.aims.infra.dto.VirtualAssetDtos.HostVm;
import com.innogrid.aims.openstack.NovaClient;
import com.innogrid.aims.openstack.OpenStackProperties;

/**
 * 가상화 자산 서비스.
 * 하이퍼바이저 호스트 → 그 위에 뜬 가상서버 계층으로 표출한다.
 *
 * 현재는 OpenStack(Nova) 기준. 배포영역·provider 구조로 설계되어 있어,
 * 추후 CB-Spider로 연결한 퍼블릭 클라우드(AWS/Azure/GCP)를 provider를 나눠 같은 응답에 합칠 수 있다.
 * OpenStack에 없는 vCenter 전용 필드(시리얼/데이터스토어/클러스터)는 null로 둔다.
 */
@Service
public class VirtualAssetService {

    private static final Logger log = LoggerFactory.getLogger(VirtualAssetService.class);

    private final OpenStackProperties props;
    private final NovaClient nova;

    public VirtualAssetService(OpenStackProperties props, NovaClient nova) {
        this.props = props;
        this.nova = nova;
    }

    /** 호스트(하이퍼바이저) 목록. */
    public List<HostSummary> listHosts() {
        List<HostSummary> hosts = new ArrayList<>();
        for (Map<String, Object> hv : list(safe(nova::listHypervisors), "hypervisors")) {
            hosts.add(toHost(hv));
        }
        return hosts;
    }

    /** 호스트 하위 가상서버 목록 (hostId = 하이퍼바이저 hostname). */
    public List<HostVm> listHostVms(String hostId) {
        List<HostVm> vms = new ArrayList<>();
        for (Map<String, Object> server : list(safe(nova::listServersAllTenants), "servers")) {
            String host = string(server.get("OS-EXT-SRV-ATTR:hypervisor_hostname"));
            if (host == null || !host.equals(hostId)) continue;
            vms.add(toVm(server));
        }
        return vms;
    }

    // ── OpenStack → DTO 매핑 ────────────────────────────────────

    private HostSummary toHost(Map<String, Object> hv) {
        String hostname = string(hv.get("hypervisor_hostname"));
        Long total = asLong(hv.get("memory_mb"));
        Long used = asLong(hv.get("memory_mb_used"));
        Long free = (total != null && used != null) ? total - used : null;
        return new HostSummary(
                props.getZoneName(), "openstack",
                hostname, hostname,
                null,                                 // 별칭 (OpenStack 없음)
                null,                                 // 시리얼 넘버 (OpenStack 없음)
                asInt(hv.get("running_vms")),         // 가상서버 수
                null,                                 // 데이터스토어 수 (OpenStack 없음)
                asInt(hv.get("vcpus")),               // vCore
                asInt(hv.get("vcpus_used")),          // 할당된 코어
                total, used, free);                   // 메모리 총량/사용량/여유
    }

    @SuppressWarnings("unchecked")
    private HostVm toVm(Map<String, Object> server) {
        List<String> ips = new ArrayList<>();
        if (server.get("addresses") instanceof Map<?, ?> addrMap) {
            for (Object addrList : addrMap.values()) {
                for (Object addr : castList(addrList)) {
                    if (addr instanceof Map<?, ?> a) {
                        String ip = string(((Map<String, Object>) a).get("addr"));
                        if (ip != null) ips.add(ip);
                    }
                }
            }
        }
        return new HostVm(
                string(server.get("id")),
                string(server.get("name")),
                null,                                 // 별칭 (OpenStack 없음)
                string(server.get("status")),         // 시스템 상태 (ACTIVE/SHUTOFF)
                ips,
                null,                                 // 클러스터 (OpenStack 없음)
                null);                                // 데이터스토어 (OpenStack 없음)
    }

    // ── 공통 ────────────────────────────────────────────────────

    private Map<String, Object> safe(java.util.function.Supplier<Map<String, Object>> call) {
        try {
            return call.get();
        } catch (Exception e) {
            log.warn("가상화 자산 OpenStack 조회 실패: {}", e.getMessage());
            return Map.of();
        }
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

    private static Integer asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return null;
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return null;
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}

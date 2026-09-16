package com.innogrid.aims.infra.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.innogrid.aims.cloudbarista.tumblebug.TumblebugClient;
import com.innogrid.aims.infra.dto.VirtualAssetDtos.HostSummary;
import com.innogrid.aims.infra.dto.VirtualAssetDtos.HostVm;
import com.innogrid.aims.openstack.NovaClient;
import com.innogrid.aims.openstack.OpenStackProperties;

/**
 * 가상화 자산 서비스.
 * 하이퍼바이저 호스트 → 그 위에 뜬 가상서버 계층으로 표출한다.
 *
 * <p>두 소스를 provider 로 구분해 합친다:
 * <ul>
 *   <li><b>OpenStack</b>: Nova 직결. 하이퍼바이저 호스트→VM 계층 + 사용률(vCore/할당코어/메모리). (변경 없음)</li>
 *   <li><b>퍼블릭 클라우드(aws/azure/gcp)</b>: CB-Tumblebug. provider+리전(=connection)을 "호스트"로 묶고 그 아래 VM 을 표출한다.
 *       공개 클라우드는 하이퍼바이저 호스트 사용률을 제공하지 않으므로 vCore/할당코어/메모리 필드는 null 이다(제공 불가).</li>
 * </ul>
 * OpenStack 은 Nova 로만 조회한다 — Tumblebug 의 openstack 커넥션도 하이퍼바이저 사용률은 주지 않으므로 중복 표출하지 않고 건너뛴다.
 */
@Service
public class VirtualAssetService {

    private static final Logger log = LoggerFactory.getLogger(VirtualAssetService.class);

    /** Tumblebug MCI(=infra) VM 목록에서 OpenStack 은 Nova 가 담당하므로 제외한다. */
    private static final String OPENSTACK_PROVIDER = "openstack";

    private final OpenStackProperties props;
    private final NovaClient nova;
    private final TumblebugClient tumblebug;

    public VirtualAssetService(OpenStackProperties props, NovaClient nova, TumblebugClient tumblebug) {
        this.props = props;
        this.nova = nova;
        this.tumblebug = tumblebug;
    }

    /** 호스트 목록 = OpenStack 하이퍼바이저(Nova) + 퍼블릭 클라우드 provider+리전(Tumblebug). */
    public List<HostSummary> listHosts() {
        List<HostSummary> hosts = new ArrayList<>();
        // 1) OpenStack — Nova 하이퍼바이저 (사용률 포함)
        for (Map<String, Object> hv : list(safe(nova::listHypervisors), "hypervisors")) {
            hosts.add(toHost(hv));
        }
        // 2) 퍼블릭 클라우드 — Tumblebug VM 을 connection(=provider+region) 별로 묶어 호스트로
        hosts.addAll(publicCloudHosts());
        return hosts;
    }

    /**
     * 호스트 하위 가상서버 목록.
     * hostId 가 퍼블릭 클라우드 connection 이면 Tumblebug VM 을, 아니면 Nova 하이퍼바이저 하위 VM 을 반환한다.
     */
    public List<HostVm> listHostVms(String hostId) {
        Map<String, List<HostVm>> publicVms = publicCloudVmsByConnection();
        if (publicVms.containsKey(hostId)) {
            return publicVms.get(hostId);
        }
        List<HostVm> vms = new ArrayList<>();
        for (Map<String, Object> server : list(safe(nova::listServersAllTenants), "servers")) {
            String host = string(server.get("OS-EXT-SRV-ATTR:hypervisor_hostname"));
            if (host == null || !host.equals(hostId)) continue;
            vms.add(toVm(server));
        }
        return vms;
    }

    // ── OpenStack(Nova) → DTO 매핑 ──────────────────────────────

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

    // ── 퍼블릭 클라우드(Tumblebug) → DTO 매핑 ───────────────────

    /** connection(=provider+region) 별 호스트 요약. VM 이 1대 이상 있는 connection 만 노출한다. */
    private List<HostSummary> publicCloudHosts() {
        Map<String, List<PublicVm>> byConnection = collectPublicCloudVms();
        List<HostSummary> hosts = new ArrayList<>();
        for (Map.Entry<String, List<PublicVm>> e : byConnection.entrySet()) {
            List<PublicVm> vms = e.getValue();
            PublicVm sample = vms.get(0);
            hosts.add(new HostSummary(
                    sample.region(),                  // 배포영역 = 리전
                    sample.provider(),                // aws | azure | gcp
                    e.getKey(),                       // hostId = connection (provider+region)
                    e.getKey(),                       // 호스트명 = connection
                    null,                             // 별칭
                    null,                             // 시리얼 (공개 클라우드 없음)
                    vms.size(),                       // 가상서버 수
                    null,                             // 데이터스토어 수
                    null,                             // vCore — 공개 클라우드 호스트 사용률 제공 불가
                    null,                             // 할당 코어 — 제공 불가
                    null, null, null));               // 메모리 총량/사용량/여유 — 제공 불가
        }
        return hosts;
    }

    /** connection 별 VM(DTO) 맵. hostId 매칭용. */
    private Map<String, List<HostVm>> publicCloudVmsByConnection() {
        Map<String, List<PublicVm>> byConnection = collectPublicCloudVms();
        Map<String, List<HostVm>> result = new LinkedHashMap<>();
        byConnection.forEach((conn, vms) -> {
            List<HostVm> dtos = new ArrayList<>();
            for (PublicVm vm : vms) {
                dtos.add(new HostVm(vm.id(), vm.name(), null, vm.status(), vm.ips(), null, null));
            }
            result.put(conn, dtos);
        });
        return result;
    }

    /**
     * 모든 네임스페이스의 MCI(=infra) VM 을 connection 별로 수집한다.
     * OpenStack provider 는 Nova 가 담당하므로 제외. Tumblebug 장애 시 빈 맵(→ 퍼블릭 칸만 비고 OpenStack 은 정상).
     */
    private Map<String, List<PublicVm>> collectPublicCloudVms() {
        Map<String, List<PublicVm>> byConnection = new LinkedHashMap<>();
        try {
            for (String ns : nsIds(tumblebug.listNamespaces())) {
                for (Map<String, Object> infra : list(tumblebug.listInfra(ns), "infra")) {
                    for (Map<String, Object> node : list(infra, "node")) {
                        PublicVm vm = toPublicVm(node);
                        if (vm == null) continue;
                        byConnection.computeIfAbsent(vm.connection(), k -> new ArrayList<>()).add(vm);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("가상화 자산 Tumblebug 조회 실패: {}", e.getMessage());
        }
        return byConnection;
    }

    private PublicVm toPublicVm(Map<String, Object> node) {
        String connection = string(node.get("connectionName"));
        String provider = string(value(nested(node, "connectionConfig"), "providerName"));
        if (connection == null || provider == null) return null;
        if (OPENSTACK_PROVIDER.equalsIgnoreCase(provider)) return null;   // OpenStack 은 Nova 담당
        String id = string(node.get("id"));
        if (id == null) return null;

        List<String> ips = new ArrayList<>();
        addIfPresent(ips, string(node.get("publicIP")));
        addIfPresent(ips, string(node.get("privateIP")));

        return new PublicVm(connection, provider, region(connection, node), id,
                string(node.get("name")) == null ? id : string(node.get("name")),
                string(node.get("status")), ips);
    }

    /** 리전: connection 명("aws-ap-northeast-2")에서 provider 접두어를 뗀 나머지. 없으면 connection 그대로. */
    private static String region(String connection, Map<String, Object> node) {
        String regionField = string(value(nested(node, "region"), "Region"));
        if (regionField != null) return regionField;
        int dash = connection.indexOf('-');
        return dash > 0 && dash < connection.length() - 1 ? connection.substring(dash + 1) : connection;
    }

    /** 퍼블릭 클라우드 VM 내부 표현. */
    private record PublicVm(String connection, String provider, String region,
                            String id, String name, String status, List<String> ips) {}

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
    private static List<String> nsIds(Map<String, Object> response) {
        if (response != null && response.get("ns") instanceof List<?> l) {
            List<String> ids = new ArrayList<>();
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    Object id = ((Map<String, Object>) m).get("id");
                    if (id != null) ids.add(id.toString());
                }
            }
            return ids;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> response, String key) {
        if (response != null && response.get(key) instanceof List<?> l) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) result.add((Map<String, Object>) m);
            }
            return result;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> source, String key) {
        return source != null && source.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Object value(Map<String, Object> source, String key) {
        return source == null ? null : source.get(key);
    }

    private static void addIfPresent(List<String> list, String v) {
        if (v != null && !v.isBlank()) list.add(v);
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

package com.innogrid.aims.openstack.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.innogrid.aims.openstack.KeystoneClient;
import com.innogrid.aims.openstack.NeutronClient;
import com.innogrid.aims.openstack.NovaClient;
import com.innogrid.aims.openstack.OpenStackProperties;

import static com.innogrid.aims.openstack.service.NeutronMappers.castList;
import static com.innogrid.aims.openstack.service.NeutronMappers.extractList;

/**
 * 통합 토폴로지 서비스 (탭클라우드잇 구조).
 *
 * 네트워크 레이어:
 *   프로젝트 → [외부 게이트웨이 포트] → 라우터 → 네트워크 → 서브넷 → 포트(VM명/IP/MAC)
 *   그룹: 외부 게이트웨이 없는 라우터 / 라우터 미연결 네트워크 / 외부 네트워크
 *
 * 컴퓨트 레이어:
 *   가용영역 → 물리노드(하이퍼바이저) → 프로젝트 → VM 카드(상태)
 *   그룹: VM 없는 프로젝트
 *
 * 관리자 전용 API(하이퍼바이저, all_tenants)는 실패해도 트리를 유지하고 해당 정보만 생략한다.
 * 현재는 단일 OpenStack 연결(자체 구축) 기준. 1세부 정보 수신 후 다중 연결로 확장.
 */
@Service
public class OpenStackTopologyService {

    private static final Logger log = LoggerFactory.getLogger(OpenStackTopologyService.class);

    private final OpenStackProperties props;
    private final KeystoneClient keystone;
    private final NeutronClient neutron;
    private final NovaClient nova;

    public OpenStackTopologyService(OpenStackProperties props, KeystoneClient keystone,
                                    NeutronClient neutron, NovaClient nova) {
        this.props = props;
        this.keystone = keystone;
        this.neutron = neutron;
        this.nova = nova;
    }

    /** 배포영역 드롭다운 목록. 다중 연결 확장 전까지는 설정된 연결 1개. */
    public List<Map<String, Object>> listDeployZones() {
        Map<String, Object> zone = new LinkedHashMap<>();
        zone.put("name", props.getZoneName());
        zone.put("provider", "openstack");
        zone.put("configured", props.isConfigured());
        return List.of(zone);
    }

    /** 통합 토폴로지 — 네트워크 레이어 + 컴퓨트 레이어. */
    public Map<String, Object> getTopology() {
        Map<String, String> projectNames = loadProjectNames();
        List<Map<String, Object>> servers = loadServers();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deployZone", props.getZoneName());
        result.put("provider", "openstack");
        result.put("networkLayer", buildNetworkLayer(projectNames, servers));
        result.put("computeLayer", buildComputeLayer(projectNames, servers));
        return result;
    }

    // ══ 네트워크 레이어 ═════════════════════════════════════════

    private Map<String, Object> buildNetworkLayer(Map<String, String> projectNames,
                                                  List<Map<String, Object>> servers) {
        List<Map<String, Object>> networks = extractList(neutron.listNetworks(), "networks");
        List<Map<String, Object>> subnets = extractList(neutron.listSubnets(), "subnets");
        List<Map<String, Object>> ports = extractList(neutron.listPorts(), "ports");
        List<Map<String, Object>> routers = extractList(neutron.listRouters(), "routers");

        Map<String, Map<String, Object>> serversById = new HashMap<>();
        for (Map<String, Object> server : servers) {
            serversById.put(string(server.get("id")), server);
        }

        Map<String, String> networkNameById = new HashMap<>();
        for (Map<String, Object> network : networks) {
            networkNameById.put(string(network.get("id")), string(network.get("name")));
        }

        // 라우터 인터페이스 포트 → 라우터가 연결한 네트워크
        Map<String, Set<String>> routerNetworkIds = new HashMap<>();
        for (Map<String, Object> port : ports) {
            String owner = string(port.get("device_owner"));
            if (owner != null && owner.startsWith("network:") && owner.contains("router_interface")) {
                routerNetworkIds
                        .computeIfAbsent(string(port.get("device_id")), k -> new HashSet<>())
                        .add(string(port.get("network_id")));
            }
        }

        Map<String, List<Map<String, Object>>> portRowsBySubnet = groupPortRowsBySubnet(ports, serversById);
        Map<String, List<Map<String, Object>>> subnetsByNetwork = groupSubnetsByNetwork(subnets, portRowsBySubnet);

        Map<String, Map<String, Object>> networkNodesById = new LinkedHashMap<>();
        for (Map<String, Object> network : networks) {
            networkNodesById.put(string(network.get("id")), toNetworkNode(network, subnetsByNetwork));
        }

        Set<String> routerConnectedNetworkIds = new HashSet<>();
        routerNetworkIds.values().forEach(routerConnectedNetworkIds::addAll);

        // 프로젝트별 조립
        Map<String, Map<String, Object>> projectNodes = new LinkedHashMap<>();
        for (Map<String, Object> router : routers) {
            Map<String, Object> projectNode = projectNode(projectNodes, projectIdOf(router), projectNames);
            listOf(projectNode, "routers").add(
                    toRouterNode(router, routerNetworkIds, networkNodesById, networkNameById));
        }
        for (Map<String, Object> network : networks) {
            String networkId = string(network.get("id"));
            Map<String, Object> projectNode = projectNode(projectNodes, projectIdOf(network), projectNames);
            if (Boolean.TRUE.equals(network.get("router:external"))) {
                listOf(projectNode, "externalNetworks").add(networkNodesById.get(networkId));
            } else if (!routerConnectedNetworkIds.contains(networkId)) {
                listOf(projectNode, "unconnectedNetworks").add(networkNodesById.get(networkId));
            }
        }

        List<Map<String, Object>> projects = new ArrayList<>(projectNodes.values());
        projects.sort(Comparator.comparing(p -> String.valueOf(p.get("name"))));

        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("projects", projects);
        return layer;
    }

    private Map<String, Object> toRouterNode(Map<String, Object> router,
                                             Map<String, Set<String>> routerNetworkIds,
                                             Map<String, Map<String, Object>> networkNodesById,
                                             Map<String, String> networkNameById) {
        Map<String, Object> node = new LinkedHashMap<>();
        String routerId = string(router.get("id"));
        node.put("id", routerId);
        node.put("name", router.get("name"));
        node.put("status", router.get("status"));
        node.put("adminStateUp", router.get("admin_state_up"));
        node.put("staticRouteCount", castList(router.get("routes")).size());
        node.put("externalGateway", toExternalGateway(router, networkNameById));

        List<Map<String, Object>> connected = new ArrayList<>();
        for (String networkId : routerNetworkIds.getOrDefault(routerId, Set.of())) {
            Map<String, Object> networkNode = networkNodesById.get(networkId);
            if (networkNode != null) {
                connected.add(networkNode);
            }
        }
        connected.sort(Comparator.comparing(n -> String.valueOf(n.get("name"))));
        node.put("networks", connected);
        return node;
    }

    /** 라우터의 외부 게이트웨이 (없으면 null → 프론트가 '외부 게이트웨이 없는 라우터' 그룹으로 묶음). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toExternalGateway(Map<String, Object> router,
                                                         Map<String, String> networkNameById) {
        Object info = router.get("external_gateway_info");
        if (!(info instanceof Map<?, ?> gateway)) return null;

        Map<String, Object> gatewayMap = (Map<String, Object>) gateway;
        String networkId = string(gatewayMap.get("network_id"));

        String ipAddress = null;
        List<Object> fixedIps = castList(gatewayMap.get("external_fixed_ips"));
        if (!fixedIps.isEmpty() && fixedIps.get(0) instanceof Map<?, ?> first) {
            ipAddress = string(((Map<String, Object>) first).get("ip_address"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("networkId", networkId);
        result.put("networkName", networkNameById.get(networkId));
        result.put("ipAddress", ipAddress);
        return result;
    }

    private Map<String, Object> toNetworkNode(Map<String, Object> network,
                                              Map<String, List<Map<String, Object>>> subnetsByNetwork) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", network.get("id"));
        node.put("name", network.get("name"));
        node.put("status", network.get("status"));
        node.put("adminStateUp", network.get("admin_state_up"));
        node.put("mtu", network.get("mtu"));
        node.put("shared", network.get("shared"));
        node.put("external", network.get("router:external"));
        node.put("subnets", subnetsByNetwork.getOrDefault(string(network.get("id")), List.of()));
        return node;
    }

    private Map<String, List<Map<String, Object>>> groupSubnetsByNetwork(
            List<Map<String, Object>> subnets,
            Map<String, List<Map<String, Object>>> portRowsBySubnet) {
        Map<String, List<Map<String, Object>>> byNetwork = new HashMap<>();
        for (Map<String, Object> subnet : subnets) {
            String subnetId = string(subnet.get("id"));
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", subnetId);
            node.put("name", subnet.get("name"));
            node.put("cidr", subnet.get("cidr"));
            node.put("gatewayIp", subnet.get("gateway_ip"));
            node.put("ports", portRowsBySubnet.getOrDefault(subnetId, List.of()));
            byNetwork.computeIfAbsent(string(subnet.get("network_id")), k -> new ArrayList<>())
                    .add(node);
        }
        return byNetwork;
    }

    /** compute 포트를 서브넷별 행(VM명/IP/MAC)으로 변환. VM 이름은 Nova, 실패 시 device_id. */
    private Map<String, List<Map<String, Object>>> groupPortRowsBySubnet(
            List<Map<String, Object>> ports, Map<String, Map<String, Object>> serversById) {
        Map<String, List<Map<String, Object>>> bySubnet = new HashMap<>();
        for (Map<String, Object> port : ports) {
            String owner = string(port.get("device_owner"));
            if (owner == null || !owner.startsWith("compute")) continue;

            String serverId = string(port.get("device_id"));
            Map<String, Object> server = serversById.get(serverId);

            for (Object entry : castList(port.get("fixed_ips"))) {
                if (!(entry instanceof Map<?, ?> fixedIp)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> fixedIpMap = (Map<String, Object>) fixedIp;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vmId", serverId);
                row.put("vmName", server != null ? server.get("name") : serverId);
                row.put("status", server != null ? server.get("status") : port.get("status"));
                row.put("ipAddress", fixedIpMap.get("ip_address"));
                row.put("macAddress", port.get("mac_address"));
                row.put("portId", port.get("id"));
                bySubnet.computeIfAbsent(string(fixedIpMap.get("subnet_id")), k -> new ArrayList<>())
                        .add(row);
            }
        }
        return bySubnet;
    }

    // ══ 컴퓨트 레이어 ═══════════════════════════════════════════

    private Map<String, Object> buildComputeLayer(Map<String, String> projectNames,
                                                  List<Map<String, Object>> servers) {
        Map<String, Object> zoneAvailability = loadZoneAvailability();
        Map<String, Map<String, Object>> hypervisors = loadHypervisors();

        // 가용영역 → 물리노드(하이퍼바이저) → 프로젝트 → VM
        Map<String, Map<String, Map<String, List<Map<String, Object>>>>> tree = new LinkedHashMap<>();
        Set<String> projectsWithVm = new HashSet<>();
        for (Map<String, Object> server : servers) {
            String zone = stringOrDefault(server.get("OS-EXT-AZ:availability_zone"), "unknown");
            String host = stringOrDefault(server.get("OS-EXT-SRV-ATTR:hypervisor_hostname"), "unknown");
            String projectId = stringOrDefault(
                    server.getOrDefault("tenant_id", server.get("project_id")), "");
            projectsWithVm.add(projectId);

            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("id", server.get("id"));
            vm.put("name", server.get("name"));
            vm.put("status", server.get("status"));

            tree.computeIfAbsent(zone, k -> new LinkedHashMap<>())
                    .computeIfAbsent(host, k -> new LinkedHashMap<>())
                    .computeIfAbsent(projectId, k -> new ArrayList<>())
                    .add(vm);
        }

        List<Map<String, Object>> zones = new ArrayList<>();
        for (Map.Entry<String, Map<String, Map<String, List<Map<String, Object>>>>> zoneEntry : tree.entrySet()) {
            List<Map<String, Object>> hosts = new ArrayList<>();
            for (Map.Entry<String, Map<String, List<Map<String, Object>>>> hostEntry
                    : zoneEntry.getValue().entrySet()) {
                List<Map<String, Object>> projects = new ArrayList<>();
                for (Map.Entry<String, List<Map<String, Object>>> projectEntry
                        : hostEntry.getValue().entrySet()) {
                    Map<String, Object> project = new LinkedHashMap<>();
                    project.put("id", projectEntry.getKey());
                    project.put("name", projectNames.getOrDefault(
                            projectEntry.getKey(), projectEntry.getKey()));
                    project.put("vms", projectEntry.getValue());
                    projects.add(project);
                }
                projects.sort(Comparator.comparing(p -> String.valueOf(p.get("name"))));

                Map<String, Object> hypervisor = hypervisors.get(hostEntry.getKey());
                Map<String, Object> host = new LinkedHashMap<>();
                host.put("name", hostEntry.getKey());
                host.put("state", hypervisor == null ? null : hypervisor.get("state"));
                host.put("status", hypervisor == null ? null : hypervisor.get("status"));
                host.put("projects", projects);
                hosts.add(host);
            }
            hosts.sort(Comparator.comparing(h -> String.valueOf(h.get("name"))));

            Map<String, Object> zone = new LinkedHashMap<>();
            zone.put("name", zoneEntry.getKey());
            zone.put("available", zoneAvailability.get(zoneEntry.getKey()));
            zone.put("hosts", hosts);
            zones.add(zone);
        }
        zones.sort(Comparator.comparing(z -> String.valueOf(z.get("name"))));

        // 그룹: VM 없는 프로젝트
        List<Map<String, Object>> projectsWithoutVm = new ArrayList<>();
        for (String projectId : new TreeSet<>(projectNames.keySet())) {
            if (!projectsWithVm.contains(projectId)) {
                Map<String, Object> project = new LinkedHashMap<>();
                project.put("id", projectId);
                project.put("name", projectNames.get(projectId));
                projectsWithoutVm.add(project);
            }
        }
        projectsWithoutVm.sort(Comparator.comparing(p -> String.valueOf(p.get("name"))));

        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("zones", zones);
        layer.put("projectsWithoutVm", projectsWithoutVm);
        return layer;
    }

    // ══ 부가 데이터 (실패해도 트리 유지) ════════════════════════

    private Map<String, String> loadProjectNames() {
        try {
            Map<String, String> names = new HashMap<>();
            for (Map<String, Object> project : extractList(keystone.listProjects(), "projects")) {
                names.put(string(project.get("id")), string(project.get("name")));
            }
            return names;
        } catch (Exception e) {
            log.warn("Keystone 프로젝트 목록 조회 실패 — project_id로 표기: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 전체 프로젝트 VM 조회. 관리자 권한이 없으면 자기 프로젝트만 조회로 대체. */
    private List<Map<String, Object>> loadServers() {
        try {
            return extractList(nova.listServersAllTenants(), "servers");
        } catch (Exception allTenantsError) {
            try {
                return extractList(nova.listServers(), "servers");
            } catch (Exception e) {
                log.warn("Nova 서버 목록 조회 실패 — VM은 device_id로 표기: {}", e.getMessage());
                return List.of();
            }
        }
    }

    private Map<String, Object> loadZoneAvailability() {
        try {
            Map<String, Object> byZone = new HashMap<>();
            for (Map<String, Object> zone
                    : extractList(nova.listAvailabilityZones(), "availabilityZoneInfo")) {
                Object state = zone.get("zoneState");
                Object available = state instanceof Map<?, ?> stateMap
                        ? ((Map<?, ?>) stateMap).get("available") : null;
                byZone.put(string(zone.get("zoneName")), available);
            }
            return byZone;
        } catch (Exception e) {
            log.warn("가용영역 조회 실패 — 상태 생략: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Map<String, Object>> loadHypervisors() {
        try {
            Map<String, Map<String, Object>> byHostname = new HashMap<>();
            for (Map<String, Object> hypervisor
                    : extractList(nova.listHypervisors(), "hypervisors")) {
                byHostname.put(string(hypervisor.get("hypervisor_hostname")), hypervisor);
            }
            return byHostname;
        } catch (Exception e) {
            log.warn("하이퍼바이저 조회 실패(관리자 권한 필요) — 상태 생략: {}", e.getMessage());
            return Map.of();
        }
    }

    // ══ 공통 ════════════════════════════════════════════════════

    private Map<String, Object> projectNode(Map<String, Map<String, Object>> nodes,
                                            String projectId, Map<String, String> projectNames) {
        return nodes.computeIfAbsent(projectId == null ? "" : projectId, id -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", id);
            node.put("name", projectNames.getOrDefault(id,
                    id.isEmpty() ? props.getProjectName() : id));
            node.put("routers", new ArrayList<Map<String, Object>>());
            node.put("unconnectedNetworks", new ArrayList<Map<String, Object>>());
            node.put("externalNetworks", new ArrayList<Map<String, Object>>());
            return node;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> node, String key) {
        return (List<Map<String, Object>>) node.get(key);
    }

    private static String projectIdOf(Map<String, Object> resource) {
        Object id = resource.getOrDefault("project_id", resource.get("tenant_id"));
        return id == null ? null : id.toString();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String stringOrDefault(Object value, String fallback) {
        String s = string(value);
        return s == null || s.isBlank() ? fallback : s;
    }
}

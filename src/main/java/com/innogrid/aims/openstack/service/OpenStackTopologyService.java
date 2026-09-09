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
import com.innogrid.aims.openstack.dto.TopologyDtos.ComputeHost;
import com.innogrid.aims.openstack.dto.TopologyDtos.ComputeLayer;
import com.innogrid.aims.openstack.dto.TopologyDtos.ComputeProject;
import com.innogrid.aims.openstack.dto.TopologyDtos.ComputeZone;
import com.innogrid.aims.openstack.dto.TopologyDtos.DeployZone;
import com.innogrid.aims.openstack.dto.TopologyDtos.ExternalGatewayRef;
import com.innogrid.aims.openstack.dto.TopologyDtos.NetworkLayer;
import com.innogrid.aims.openstack.dto.TopologyDtos.NetworkNode;
import com.innogrid.aims.openstack.dto.TopologyDtos.NetworkProject;
import com.innogrid.aims.openstack.dto.TopologyDtos.PortRow;
import com.innogrid.aims.openstack.dto.TopologyDtos.ProjectRef;
import com.innogrid.aims.openstack.dto.TopologyDtos.RouterNode;
import com.innogrid.aims.openstack.dto.TopologyDtos.SubnetNode;
import com.innogrid.aims.openstack.dto.TopologyDtos.TopologyTree;
import com.innogrid.aims.openstack.dto.TopologyDtos.VmSummary;

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
 * 노드 클릭 시 상세는 {@link OpenStackTopologyDetailService} 가 담당한다.
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
    public List<DeployZone> listDeployZones() {
        return List.of(new DeployZone(props.getZoneName(), "openstack", props.isConfigured()));
    }

    /** 통합 토폴로지 — 네트워크 레이어 + 컴퓨트 레이어. */
    public TopologyTree getTopology() {
        Map<String, String> projectNames = loadProjectNames();
        List<Map<String, Object>> servers = loadServers();

        return new TopologyTree(props.getZoneName(), "openstack",
                buildNetworkLayer(projectNames, servers),
                buildComputeLayer(projectNames, servers));
    }

    // ══ 네트워크 레이어 ═════════════════════════════════════════

    private NetworkLayer buildNetworkLayer(Map<String, String> projectNames,
                                           List<Map<String, Object>> servers) {
        List<Map<String, Object>> networks = extractList(neutron.listNetworks(), "networks");
        List<Map<String, Object>> subnets = extractList(neutron.listSubnets(), "subnets");
        List<Map<String, Object>> ports = extractList(neutron.listPorts(), "ports");
        List<Map<String, Object>> routers = extractList(neutron.listRouters(), "routers");

        Map<String, Map<String, Object>> serversById = indexById(servers);

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

        Map<String, List<PortRow>> portRowsBySubnet = groupPortRowsBySubnet(ports, serversById);
        Map<String, List<SubnetNode>> subnetsByNetwork = groupSubnetsByNetwork(subnets, portRowsBySubnet);

        Map<String, NetworkNode> networkNodesById = new LinkedHashMap<>();
        for (Map<String, Object> network : networks) {
            networkNodesById.put(string(network.get("id")), toNetworkNode(network, subnetsByNetwork));
        }

        Set<String> routerConnectedNetworkIds = new HashSet<>();
        routerNetworkIds.values().forEach(routerConnectedNetworkIds::addAll);

        // 프로젝트별 조립용 누적 버킷
        Map<String, ProjectAccumulator> byProject = new LinkedHashMap<>();

        for (Map<String, Object> router : routers) {
            ProjectAccumulator acc = accumulator(byProject, projectIdOf(router), projectNames);
            RouterNode routerNode = toRouterNode(router, routerNetworkIds, networkNodesById, networkNameById);
            if (routerNode.externalGateway() == null) {
                acc.routersWithoutGateway.add(routerNode);
            } else {
                acc.routers.add(routerNode);
            }
        }
        for (Map<String, Object> network : networks) {
            String networkId = string(network.get("id"));
            ProjectAccumulator acc = accumulator(byProject, projectIdOf(network), projectNames);
            if (Boolean.TRUE.equals(network.get("router:external"))) {
                acc.externalNetworks.add(networkNodesById.get(networkId));
            } else if (!routerConnectedNetworkIds.contains(networkId)) {
                acc.unconnectedNetworks.add(networkNodesById.get(networkId));
            }
        }

        List<NetworkProject> projects = new ArrayList<>();
        for (ProjectAccumulator acc : byProject.values()) {
            projects.add(new NetworkProject(acc.id, acc.name,
                    acc.routers, acc.routersWithoutGateway,
                    acc.unconnectedNetworks, acc.externalNetworks));
        }
        projects.sort(Comparator.comparing(NetworkProject::name, Comparator.nullsLast(Comparator.naturalOrder())));
        return new NetworkLayer(projects);
    }

    private RouterNode toRouterNode(Map<String, Object> router,
                                    Map<String, Set<String>> routerNetworkIds,
                                    Map<String, NetworkNode> networkNodesById,
                                    Map<String, String> networkNameById) {
        String routerId = string(router.get("id"));

        List<NetworkNode> connected = new ArrayList<>();
        for (String networkId : routerNetworkIds.getOrDefault(routerId, Set.of())) {
            NetworkNode networkNode = networkNodesById.get(networkId);
            if (networkNode != null) {
                connected.add(networkNode);
            }
        }
        connected.sort(Comparator.comparing(NetworkNode::name, Comparator.nullsLast(Comparator.naturalOrder())));

        return new RouterNode(routerId, string(router.get("name")), string(router.get("status")),
                asBoolean(router.get("admin_state_up")), castList(router.get("routes")).size(),
                toExternalGateway(router, networkNameById), connected);
    }

    /** 라우터의 외부 게이트웨이 (없으면 null → '외부 게이트웨이 없는 라우터' 그룹). */
    @SuppressWarnings("unchecked")
    private static ExternalGatewayRef toExternalGateway(Map<String, Object> router,
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
        return new ExternalGatewayRef(networkId, networkNameById.get(networkId), ipAddress);
    }

    private NetworkNode toNetworkNode(Map<String, Object> network,
                                      Map<String, List<SubnetNode>> subnetsByNetwork) {
        return new NetworkNode(
                string(network.get("id")), string(network.get("name")), string(network.get("status")),
                asBoolean(network.get("admin_state_up")), asInteger(network.get("mtu")),
                asBoolean(network.get("shared")), asBoolean(network.get("router:external")),
                subnetsByNetwork.getOrDefault(string(network.get("id")), List.of()));
    }

    private Map<String, List<SubnetNode>> groupSubnetsByNetwork(
            List<Map<String, Object>> subnets, Map<String, List<PortRow>> portRowsBySubnet) {
        Map<String, List<SubnetNode>> byNetwork = new HashMap<>();
        for (Map<String, Object> subnet : subnets) {
            String subnetId = string(subnet.get("id"));
            SubnetNode node = new SubnetNode(subnetId, string(subnet.get("name")),
                    string(subnet.get("cidr")), string(subnet.get("gateway_ip")),
                    portRowsBySubnet.getOrDefault(subnetId, List.of()));
            byNetwork.computeIfAbsent(string(subnet.get("network_id")), k -> new ArrayList<>()).add(node);
        }
        return byNetwork;
    }

    /** compute 포트를 서브넷별 행(VM명/IP/MAC)으로 변환. VM 이름은 Nova, 실패 시 device_id. */
    private Map<String, List<PortRow>> groupPortRowsBySubnet(
            List<Map<String, Object>> ports, Map<String, Map<String, Object>> serversById) {
        Map<String, List<PortRow>> bySubnet = new HashMap<>();
        for (Map<String, Object> port : ports) {
            String owner = string(port.get("device_owner"));
            if (owner == null || !owner.startsWith("compute")) continue;

            String serverId = string(port.get("device_id"));
            Map<String, Object> server = serversById.get(serverId);
            String vmName = server != null ? string(server.get("name")) : serverId;
            String status = server != null ? string(server.get("status")) : string(port.get("status"));

            for (Object entry : castList(port.get("fixed_ips"))) {
                if (!(entry instanceof Map<?, ?> fixedIp)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> fixedIpMap = (Map<String, Object>) fixedIp;

                PortRow row = new PortRow(serverId, vmName, status,
                        string(fixedIpMap.get("ip_address")), string(port.get("mac_address")),
                        string(port.get("id")));
                bySubnet.computeIfAbsent(string(fixedIpMap.get("subnet_id")), k -> new ArrayList<>()).add(row);
            }
        }
        return bySubnet;
    }

    // ══ 컴퓨트 레이어 ═══════════════════════════════════════════

    private ComputeLayer buildComputeLayer(Map<String, String> projectNames,
                                           List<Map<String, Object>> servers) {
        Map<String, Object> zoneAvailability = loadZoneAvailability();
        Map<String, Map<String, Object>> hypervisors = loadHypervisors();

        // 가용영역 → 물리노드(하이퍼바이저) → 프로젝트 → VM
        Map<String, Map<String, Map<String, List<VmSummary>>>> tree = new LinkedHashMap<>();
        Set<String> projectsWithVm = new HashSet<>();
        for (Map<String, Object> server : servers) {
            String zone = stringOrDefault(server.get("OS-EXT-AZ:availability_zone"), "unknown");
            String host = stringOrDefault(server.get("OS-EXT-SRV-ATTR:hypervisor_hostname"), "unknown");
            String projectId = stringOrDefault(server.getOrDefault("tenant_id", server.get("project_id")), "");
            projectsWithVm.add(projectId);

            VmSummary vm = new VmSummary(string(server.get("id")), string(server.get("name")),
                    string(server.get("status")));
            tree.computeIfAbsent(zone, k -> new LinkedHashMap<>())
                    .computeIfAbsent(host, k -> new LinkedHashMap<>())
                    .computeIfAbsent(projectId, k -> new ArrayList<>())
                    .add(vm);
        }

        List<ComputeZone> zones = new ArrayList<>();
        for (var zoneEntry : tree.entrySet()) {
            List<ComputeHost> hosts = new ArrayList<>();
            for (var hostEntry : zoneEntry.getValue().entrySet()) {
                List<ComputeProject> projects = new ArrayList<>();
                for (var projectEntry : hostEntry.getValue().entrySet()) {
                    projects.add(new ComputeProject(projectEntry.getKey(),
                            projectNames.getOrDefault(projectEntry.getKey(), projectEntry.getKey()),
                            projectEntry.getValue()));
                }
                projects.sort(Comparator.comparing(ComputeProject::name, Comparator.nullsLast(Comparator.naturalOrder())));

                Map<String, Object> hypervisor = hypervisors.get(hostEntry.getKey());
                hosts.add(new ComputeHost(hostEntry.getKey(),
                        hypervisor == null ? null : string(hypervisor.get("state")),
                        hypervisor == null ? null : string(hypervisor.get("status")),
                        projects));
            }
            hosts.sort(Comparator.comparing(ComputeHost::name, Comparator.nullsLast(Comparator.naturalOrder())));
            zones.add(new ComputeZone(zoneEntry.getKey(), asBoolean(zoneAvailability.get(zoneEntry.getKey())), hosts));
        }
        zones.sort(Comparator.comparing(ComputeZone::name, Comparator.nullsLast(Comparator.naturalOrder())));

        // 그룹: VM 없는 프로젝트
        List<ProjectRef> projectsWithoutVm = new ArrayList<>();
        for (String projectId : new TreeSet<>(projectNames.keySet())) {
            if (!projectsWithVm.contains(projectId)) {
                projectsWithoutVm.add(new ProjectRef(projectId, projectNames.get(projectId)));
            }
        }
        projectsWithoutVm.sort(Comparator.comparing(ProjectRef::name, Comparator.nullsLast(Comparator.naturalOrder())));

        return new ComputeLayer(zones, projectsWithoutVm);
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
            for (Map<String, Object> zone : extractList(nova.listAvailabilityZones(), "availabilityZoneInfo")) {
                Object state = zone.get("zoneState");
                Object available = state instanceof Map<?, ?> stateMap ? ((Map<?, ?>) stateMap).get("available") : null;
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
            for (Map<String, Object> hypervisor : extractList(nova.listHypervisors(), "hypervisors")) {
                byHostname.put(string(hypervisor.get("hypervisor_hostname")), hypervisor);
            }
            return byHostname;
        } catch (Exception e) {
            log.warn("하이퍼바이저 조회 실패(관리자 권한 필요) — 상태 생략: {}", e.getMessage());
            return Map.of();
        }
    }

    // ══ 공통 ════════════════════════════════════════════════════

    /** 네트워크 레이어 프로젝트별 누적 버킷 (record 생성 전 임시 컨테이너). */
    private static final class ProjectAccumulator {
        final String id;
        final String name;
        final List<RouterNode> routers = new ArrayList<>();
        final List<RouterNode> routersWithoutGateway = new ArrayList<>();
        final List<NetworkNode> unconnectedNetworks = new ArrayList<>();
        final List<NetworkNode> externalNetworks = new ArrayList<>();
        ProjectAccumulator(String id, String name) { this.id = id; this.name = name; }
    }

    private ProjectAccumulator accumulator(Map<String, ProjectAccumulator> nodes,
                                           String projectId, Map<String, String> projectNames) {
        String key = projectId == null ? "" : projectId;
        return nodes.computeIfAbsent(key, id ->
                new ProjectAccumulator(id, projectNames.getOrDefault(id,
                        id.isEmpty() ? props.getProjectName() : id)));
    }

    private static Map<String, Map<String, Object>> indexById(List<Map<String, Object>> servers) {
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> server : servers) {
            byId.put(string(server.get("id")), server);
        }
        return byId;
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

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value == null) return null;
        return Boolean.parseBoolean(value.toString());
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return null;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package com.innogrid.aims.openstack.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.openstack.GlanceClient;
import com.innogrid.aims.openstack.KeystoneClient;
import com.innogrid.aims.openstack.NeutronClient;
import com.innogrid.aims.openstack.NovaClient;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.GatewayPortDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.HypervisorDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.NetworkDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.PortDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.ProjectDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.ProjectVm;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.RouterDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.RouterInterface;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.SecurityGroupRule;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.ServerDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.ServerIp;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.StaticRoute;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.SubnetDetail;
import com.innogrid.aims.openstack.dto.TopologyDtos.ExternalGatewayRef;

import static com.innogrid.aims.openstack.service.NeutronMappers.castList;
import static com.innogrid.aims.openstack.service.NeutronMappers.extractList;
import static com.innogrid.aims.openstack.service.NeutronMappers.extractMap;

/**
 * 통합 토폴로지 노드 클릭 상세 서비스.
 * 라우터/네트워크/포트/VM/하이퍼바이저/프로젝트 6종 상세를 OpenStack API로 조립한다.
 */
@Service
public class OpenStackTopologyDetailService {

    private static final Logger log = LoggerFactory.getLogger(OpenStackTopologyDetailService.class);

    private final KeystoneClient keystone;
    private final NeutronClient neutron;
    private final NovaClient nova;
    private final GlanceClient glance;

    public OpenStackTopologyDetailService(KeystoneClient keystone, NeutronClient neutron,
                                          NovaClient nova, GlanceClient glance) {
        this.keystone = keystone;
        this.neutron = neutron;
        this.nova = nova;
        this.glance = glance;
    }

    // ══ 라우터 상세 ═════════════════════════════════════════════

    public RouterDetail getRouterDetail(String routerId) {
        Map<String, Object> router = extractMap(neutron.getRouter(routerId), "router");
        if (router.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "라우터를 찾을 수 없습니다: " + routerId);
        }

        Map<String, String> networkNames = indexName(extractList(neutron.listNetworks(), "networks"));
        Map<String, String> subnetNames = indexName(extractList(neutron.listSubnets(), "subnets"));

        // 내부 인터페이스 = 라우터에 연결된 포트의 fixed_ips
        List<RouterInterface> interfaces = new ArrayList<>();
        for (Map<String, Object> port : extractList(neutron.listPortsByDevice(routerId), "ports")) {
            String owner = string(port.get("device_owner"));
            if (owner != null && owner.contains("gateway")) continue; // 외부 게이트웨이 포트 제외
            String networkId = string(port.get("network_id"));
            for (Object entry : castList(port.get("fixed_ips"))) {
                if (!(entry instanceof Map<?, ?> ip)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> ipMap = (Map<String, Object>) ip;
                String subnetId = string(ipMap.get("subnet_id"));
                interfaces.add(new RouterInterface(string(ipMap.get("ip_address")),
                        networkId, networkNames.get(networkId),
                        subnetId, subnetNames.get(subnetId)));
            }
        }

        List<StaticRoute> staticRoutes = new ArrayList<>();
        for (Object route : castList(router.get("routes"))) {
            if (route instanceof Map<?, ?> r) {
                staticRoutes.add(new StaticRoute(
                        string(((Map<?, ?>) r).get("destination")),
                        string(((Map<?, ?>) r).get("nexthop"))));
            }
        }

        return new RouterDetail(string(router.get("id")), string(router.get("name")),
                string(router.get("status")), asBoolean(router.get("admin_state_up")),
                asBoolean(router.get("distributed")), string(router.get("created_at")),
                externalGateway(router, networkNames), interfaces, staticRoutes);
    }

    @SuppressWarnings("unchecked")
    private static ExternalGatewayRef externalGateway(Map<String, Object> router, Map<String, String> networkNames) {
        Object info = router.get("external_gateway_info");
        if (!(info instanceof Map<?, ?> gw)) return null;
        Map<String, Object> gateway = (Map<String, Object>) gw;
        String networkId = string(gateway.get("network_id"));
        String ip = null;
        List<Object> fixedIps = castList(gateway.get("external_fixed_ips"));
        if (!fixedIps.isEmpty() && fixedIps.get(0) instanceof Map<?, ?> first) {
            ip = string(((Map<String, Object>) first).get("ip_address"));
        }
        return new ExternalGatewayRef(networkId, networkNames.get(networkId), ip);
    }

    // ══ 네트워크 상세 ═══════════════════════════════════════════

    public NetworkDetail getNetworkDetail(String networkId) {
        Map<String, Object> network = extractMap(neutron.getNetwork(networkId), "network");
        if (network.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "네트워크를 찾을 수 없습니다: " + networkId);
        }

        List<Map<String, Object>> ports = extractList(neutron.listPortsByNetwork(networkId), "ports");
        Map<String, String> serverNames = serverNamesByPortDevice(ports);

        // 서브넷별 포트 수 집계
        Map<String, Integer> portCountBySubnet = new HashMap<>();
        List<PortDetail> portDetails = new ArrayList<>();
        Map<String, String> subnetNames = new HashMap<>();
        for (Map<String, Object> subnet : extractList(neutron.listSubnetsByNetwork(networkId), "subnets")) {
            subnetNames.put(string(subnet.get("id")), string(subnet.get("name")));
        }

        for (Map<String, Object> port : ports) {
            String owner = string(port.get("device_owner"));
            boolean isVm = owner != null && owner.startsWith("compute");
            String deviceId = string(port.get("device_id"));
            for (Object entry : castList(port.get("fixed_ips"))) {
                if (!(entry instanceof Map<?, ?> ip)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> ipMap = (Map<String, Object>) ip;
                String subnetId = string(ipMap.get("subnet_id"));
                portCountBySubnet.merge(subnetId, 1, Integer::sum);
                portDetails.add(new PortDetail(subnetId, subnetNames.get(subnetId),
                        isVm ? deviceId : null,
                        isVm ? serverNames.getOrDefault(deviceId, deviceId) : null,
                        string(ipMap.get("ip_address")), string(port.get("mac_address")),
                        string(port.get("status")), asBoolean(port.get("admin_state_up"))));
            }
        }

        List<SubnetDetail> subnetDetails = new ArrayList<>();
        for (Map<String, Object> subnet : extractList(neutron.listSubnetsByNetwork(networkId), "subnets")) {
            String subnetId = string(subnet.get("id"));
            subnetDetails.add(new SubnetDetail(subnetId, string(subnet.get("name")),
                    string(subnet.get("cidr")), asInteger(subnet.get("ip_version")),
                    string(subnet.get("gateway_ip")), asBoolean(subnet.get("enable_dhcp")),
                    portCountBySubnet.getOrDefault(subnetId, 0)));
        }

        return new NetworkDetail(string(network.get("id")), string(network.get("name")),
                string(network.get("status")), asBoolean(network.get("admin_state_up")),
                string(network.get("provider:network_type")), asInteger(network.get("mtu")),
                asBoolean(network.get("shared")), asBoolean(network.get("router:external")),
                subnetDetails, portDetails);
    }

    // ══ 외부 게이트웨이 포트 상세 ═══════════════════════════════

    public GatewayPortDetail getPortDetail(String portId) {
        Map<String, Object> port = extractMap(neutron.getPort(portId), "port");
        if (port.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "포트를 찾을 수 없습니다: " + portId);
        }
        String ip = null;
        List<Object> fixedIps = castList(port.get("fixed_ips"));
        if (!fixedIps.isEmpty() && fixedIps.get(0) instanceof Map<?, ?> first) {
            @SuppressWarnings("unchecked")
            Map<String, Object> firstMap = (Map<String, Object>) first;
            ip = string(firstMap.get("ip_address"));
        }
        return new GatewayPortDetail(string(port.get("id")), string(port.get("name")),
                ip, string(port.get("status")), string(port.get("mac_address")));
    }

    // ══ 가상머신 상세 ═══════════════════════════════════════════

    public ServerDetail getServerDetail(String serverId) {
        Map<String, Object> server = extractMap(nova.getServer(serverId), "server");
        if (server.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "VM을 찾을 수 없습니다: " + serverId);
        }

        Flavor flavor = resolveFlavor(server.get("flavor"));
        String imageName = resolveImageName(server.get("image"));
        List<ServerIp> ips = serverIps(server);
        List<SecurityGroupRule> rules = securityGroupRules(server);

        return new ServerDetail(string(server.get("id")), string(server.get("name")),
                string(server.get("status")), powerState(server.get("OS-EXT-STS:power_state")),
                flavor.vcpus, flavor.ramMb, flavor.diskGb, imageName, flavor.name, ips, rules);
    }

    private record Flavor(Integer vcpus, Integer ramMb, Integer diskGb, String name) {}

    @SuppressWarnings("unchecked")
    private Flavor resolveFlavor(Object flavorObj) {
        if (!(flavorObj instanceof Map<?, ?> f)) return new Flavor(null, null, null, null);
        Map<String, Object> flavor = (Map<String, Object>) f;
        // microversion 2.47+ : vcpus/ram/disk/original_name 임베드
        if (flavor.get("vcpus") != null) {
            return new Flavor(asInteger(flavor.get("vcpus")), asInteger(flavor.get("ram")),
                    asInteger(flavor.get("disk")), string(flavor.get("original_name")));
        }
        // 구버전 : id 만 → 별도 조회
        String flavorId = string(flavor.get("id"));
        if (flavorId != null) {
            try {
                Map<String, Object> detail = extractMap(nova.getFlavor(flavorId), "flavor");
                return new Flavor(asInteger(detail.get("vcpus")), asInteger(detail.get("ram")),
                        asInteger(detail.get("disk")), string(detail.get("name")));
            } catch (Exception e) {
                log.warn("flavor 조회 실패 {}: {}", flavorId, e.getMessage());
            }
        }
        return new Flavor(null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private String resolveImageName(Object imageObj) {
        if (!(imageObj instanceof Map<?, ?> img)) return null;
        String imageId = string(((Map<String, Object>) img).get("id"));
        if (imageId == null || imageId.isBlank()) return null; // 볼륨 부팅 VM
        if (!glance.isConfigured()) return imageId;
        try {
            Object name = glance.getImage(imageId).get("name");
            return name != null ? name.toString() : imageId;
        } catch (Exception e) {
            log.warn("이미지 조회 실패 {}: {}", imageId, e.getMessage());
            return imageId;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ServerIp> serverIps(Map<String, Object> server) {
        List<ServerIp> ips = new ArrayList<>();
        Object addresses = server.get("addresses");
        if (addresses instanceof Map<?, ?> addrMap) {
            for (Map.Entry<?, ?> entry : addrMap.entrySet()) {
                String networkName = string(entry.getKey());
                for (Object addr : castList(entry.getValue())) {
                    if (addr instanceof Map<?, ?> a) {
                        ips.add(new ServerIp(networkName, string(((Map<String, Object>) a).get("addr"))));
                    }
                }
            }
        }
        return ips;
    }

    private List<SecurityGroupRule> securityGroupRules(Map<String, Object> server) {
        // 서버의 보안그룹 이름 수집
        List<String> groupNames = new ArrayList<>();
        for (Object sg : castList(server.get("security_groups"))) {
            if (sg instanceof Map<?, ?> g) {
                groupNames.add(string(((Map<?, ?>) g).get("name")));
            }
        }
        if (groupNames.isEmpty()) return List.of();

        List<SecurityGroupRule> rules = new ArrayList<>();
        try {
            for (Map<String, Object> group : extractList(neutron.listSecurityGroups(), "security_groups")) {
                String groupName = string(group.get("name"));
                if (!groupNames.contains(groupName)) continue;
                for (Object r : castList(group.get("security_group_rules"))) {
                    if (r instanceof Map<?, ?> rule) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> ruleMap = (Map<String, Object>) rule;
                        rules.add(new SecurityGroupRule(groupName,
                                string(ruleMap.get("direction")), string(ruleMap.get("ethertype")),
                                string(ruleMap.get("protocol")), portRange(ruleMap),
                                string(ruleMap.get("remote_ip_prefix")), string(ruleMap.get("remote_group_id"))));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("보안그룹 규칙 조회 실패: {}", e.getMessage());
        }
        return rules;
    }

    private static String portRange(Map<String, Object> rule) {
        Integer min = asInteger(rule.get("port_range_min"));
        Integer max = asInteger(rule.get("port_range_max"));
        if (min == null && max == null) return null; // 전체
        if (min != null && min.equals(max)) return String.valueOf(min);
        return min + "-" + max;
    }

    // ══ 물리노드·하이퍼바이저 상세 ═════════════════════════════

    public HypervisorDetail getHypervisorDetail(String hostname) {
        Map<String, Object> found = null;
        for (Map<String, Object> hv : extractList(nova.listHypervisors(), "hypervisors")) {
            if (hostname.equals(string(hv.get("hypervisor_hostname")))) {
                found = hv;
                break;
            }
        }
        if (found == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "하이퍼바이저를 찾을 수 없습니다: " + hostname);
        }

        return new HypervisorDetail(string(found.get("hypervisor_hostname")), string(found.get("host_ip")),
                availabilityZoneOf(hostname, found), "nova-compute",
                string(found.get("state")), string(found.get("status")),
                string(found.get("hypervisor_type")),
                asInteger(found.get("vcpus_used")), asInteger(found.get("vcpus")),
                asLong(found.get("memory_mb_used")), asLong(found.get("memory_mb")),
                asLong(found.get("local_gb_used")), asLong(found.get("local_gb")),
                asInteger(found.get("running_vms")));
    }

    /**
     * 하이퍼바이저의 소속 가용영역.
     * 1차: 해당 호스트에 떠 있는 VM의 OS-EXT-AZ:availability_zone (트리와 동일한 검증된 경로).
     * 2차: os-availability-zone/detail 의 hosts 매핑 (VM 없는 호스트 대비).
     */
    @SuppressWarnings("unchecked")
    private String availabilityZoneOf(String hostname, Map<String, Object> hypervisor) {
        try {
            for (Map<String, Object> server : extractList(nova.listServersAllTenants(), "servers")) {
                if (hostname.equals(string(server.get("OS-EXT-SRV-ATTR:hypervisor_hostname")))) {
                    String az = string(server.get("OS-EXT-AZ:availability_zone"));
                    if (az != null && !az.isBlank()) return az;
                }
            }
        } catch (Exception e) {
            log.warn("서버 기반 가용영역 매핑 실패: {}", e.getMessage());
        }

        String serviceHost = null;
        if (hypervisor.get("service") instanceof Map<?, ?> svc) {
            serviceHost = string(((Map<?, ?>) svc).get("host"));
        }
        String host = serviceHost != null ? serviceHost : hostname;
        try {
            for (Map<String, Object> zone : extractList(nova.listAvailabilityZonesDetail(), "availabilityZoneInfo")) {
                if (zone.get("hosts") instanceof Map<?, ?> hostMap && hostMap.containsKey(host)) {
                    return string(zone.get("zoneName"));
                }
            }
        } catch (Exception e) {
            log.warn("가용영역 상세 매핑 실패: {}", e.getMessage());
        }
        return null;
    }

    // ══ 프로젝트 상세 ═══════════════════════════════════════════

    public ProjectDetail getProjectDetail(String projectId) {
        Map<String, Object> project = extractMap(keystone.getProject(projectId), "project");
        if (project.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다: " + projectId);
        }

        // 해당 프로젝트의 VM 목록
        List<ProjectVm> vms = new ArrayList<>();
        int running = 0;
        for (Map<String, Object> server : extractList(nova.listServersAllTenants(), "servers")) {
            String pid = string(server.getOrDefault("tenant_id", server.get("project_id")));
            if (!projectId.equals(pid)) continue;
            Flavor flavor = resolveFlavor(server.get("flavor"));
            String status = string(server.get("status"));
            if ("ACTIVE".equalsIgnoreCase(status)) running++;
            vms.add(new ProjectVm(string(server.get("id")), string(server.get("name")), status,
                    powerState(server.get("OS-EXT-STS:power_state")),
                    flavor.vcpus, flavor.ramMb, flavor.diskGb, serverIps(server)));
        }

        String domain = string(project.get("domain_id"));
        if (project.get("domain") instanceof Map<?, ?> d) {
            domain = string(((Map<?, ?>) d).get("name"));
        }

        return new ProjectDetail(string(project.get("id")), string(project.get("name")),
                domain, asBoolean(project.get("enabled")), running, vms.size(),
                string(project.get("created_at")), vms);
    }

    // ══ 공통 헬퍼 ═══════════════════════════════════════════════

    private Map<String, String> serverNamesByPortDevice(List<Map<String, Object>> ports) {
        // 네트워크 상세에서 포트의 device_id(VM) → 이름 매핑. 전체 서버 목록 1회 조회.
        Map<String, String> names = new HashMap<>();
        try {
            for (Map<String, Object> server : extractList(nova.listServersAllTenants(), "servers")) {
                names.put(string(server.get("id")), string(server.get("name")));
            }
        } catch (Exception e) {
            log.warn("네트워크 상세 VM 이름 조회 실패 — device_id로 표기: {}", e.getMessage());
        }
        return names;
    }

    private static Map<String, String> indexName(List<Map<String, Object>> items) {
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> item : items) {
            names.put(string(item.get("id")), string(item.get("name")));
        }
        return names;
    }

    /** Nova power_state 정수 → 라벨. */
    private static String powerState(Object value) {
        Integer code = asInteger(value);
        if (code == null) return null;
        return switch (code) {
            case 1 -> "Running";
            case 3 -> "Paused";
            case 4 -> "Shutdown";
            case 7 -> "Suspended";
            case 6 -> "Crashed";
            default -> "NoState";
        };
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
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

    private static Long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

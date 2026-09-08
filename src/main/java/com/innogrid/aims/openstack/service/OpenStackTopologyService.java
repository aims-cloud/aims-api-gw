package com.innogrid.aims.openstack.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * OpenStack 자원을 프로젝트 → 네트워크 → 서브넷 → VM 트리로 조립한다.
 *
 * - 프로젝트: Keystone (조회 실패 시 설정된 프로젝트 1개로 대체)
 * - 네트워크/서브넷/포트: Neutron
 * - VM 이름/상태: Nova (미설정/실패 시 포트의 device_id로 대체 표기)
 *
 * 현재는 단일 OpenStack 연결(자체 구축) 기준. 1세부 OpenStack 정보 수신 후
 * 다중 연결(배포영역 목록)로 확장한다. vmware 등 타 플랫폼은 범위 외.
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

    /** 통합 토폴로지 트리 — 프로젝트 → 네트워크 → 서브넷 → VM. */
    public Map<String, Object> getTopology() {
        Map<String, String> projectNames = loadProjectNames();
        Map<String, Map<String, Object>> servers = loadServers();

        List<Map<String, Object>> networks = extractList(neutron.listNetworks(), "networks");
        List<Map<String, Object>> subnets = extractList(neutron.listSubnets(), "subnets");
        List<Map<String, Object>> ports = extractList(neutron.listPorts(), "ports");

        Map<String, List<Map<String, Object>>> vmsBySubnet = groupVmsBySubnet(ports, servers);
        Map<String, List<Map<String, Object>>> subnetsByNetwork = groupSubnetsByNetwork(subnets, vmsBySubnet);

        // 네트워크를 프로젝트별로 묶는다. 프로젝트 이름을 모르면 project_id로 표기.
        Map<String, List<Map<String, Object>>> networksByProject = new LinkedHashMap<>();
        for (Map<String, Object> network : networks) {
            String projectId = string(network.getOrDefault("project_id", network.get("tenant_id")));
            networksByProject
                    .computeIfAbsent(projectId == null ? "" : projectId, k -> new ArrayList<>())
                    .add(toNetworkNode(network, subnetsByNetwork));
        }

        List<Map<String, Object>> projects = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : networksByProject.entrySet()) {
            Map<String, Object> project = new LinkedHashMap<>();
            project.put("id", entry.getKey());
            project.put("name", projectNames.getOrDefault(entry.getKey(),
                    entry.getKey().isEmpty() ? props.getProjectName() : entry.getKey()));
            project.put("networks", entry.getValue());
            projects.add(project);
        }
        projects.sort(Comparator.comparing(p -> String.valueOf(p.get("name"))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deployZone", props.getZoneName());
        result.put("provider", "openstack");
        result.put("projects", projects);
        return result;
    }

    // ── 계층 조립 ───────────────────────────────────────────────

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
            Map<String, List<Map<String, Object>>> vmsBySubnet) {
        Map<String, List<Map<String, Object>>> byNetwork = new HashMap<>();
        for (Map<String, Object> subnet : subnets) {
            Map<String, Object> node = new LinkedHashMap<>();
            String subnetId = string(subnet.get("id"));
            node.put("id", subnetId);
            node.put("name", subnet.get("name"));
            node.put("cidr", subnet.get("cidr"));
            node.put("gatewayIp", subnet.get("gateway_ip"));
            node.put("vms", vmsBySubnet.getOrDefault(subnetId, List.of()));
            byNetwork.computeIfAbsent(string(subnet.get("network_id")), k -> new ArrayList<>())
                    .add(node);
        }
        return byNetwork;
    }

    /** compute 포트를 서브넷별 VM 행으로 변환. VM 이름/상태는 Nova에서, 실패 시 device_id 표기. */
    private Map<String, List<Map<String, Object>>> groupVmsBySubnet(
            List<Map<String, Object>> ports, Map<String, Map<String, Object>> servers) {
        Map<String, List<Map<String, Object>>> bySubnet = new HashMap<>();
        for (Map<String, Object> port : ports) {
            String deviceOwner = string(port.get("device_owner"));
            if (deviceOwner == null || !deviceOwner.startsWith("compute")) continue;

            String serverId = string(port.get("device_id"));
            Map<String, Object> server = servers.get(serverId);

            for (Object entry : castList(port.get("fixed_ips"))) {
                if (!(entry instanceof Map<?, ?> fixedIp)) continue;
                String subnetId = string(fixedIp.get("subnet_id"));

                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("id", serverId);
                vm.put("name", server != null ? server.get("name") : serverId);
                vm.put("status", server != null ? server.get("status") : port.get("status"));
                vm.put("ipAddress", fixedIp.get("ip_address"));
                vm.put("portId", port.get("id"));
                bySubnet.computeIfAbsent(subnetId, k -> new ArrayList<>()).add(vm);
            }
        }
        return bySubnet;
    }

    // ── 부가 데이터 (실패해도 트리는 유지) ──────────────────────

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

    private Map<String, Map<String, Object>> loadServers() {
        try {
            Map<String, Map<String, Object>> byId = new HashMap<>();
            for (Map<String, Object> server : extractList(nova.listServers(), "servers")) {
                byId.put(string(server.get("id")), server);
            }
            return byId;
        } catch (Exception e) {
            log.warn("Nova 서버 목록 조회 실패 — VM은 device_id로 표기: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}

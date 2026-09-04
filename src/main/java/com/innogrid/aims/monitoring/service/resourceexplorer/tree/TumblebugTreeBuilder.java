package com.innogrid.aims.monitoring.service.resourceexplorer.tree;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.innogrid.aims.cloudbarista.tumblebug.TumblebugClient;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.ResourceExplorerProperties;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeType;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MonitoringCapabilityResolver.GpuDevice;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MonitoringCapabilityResolver.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자원 탐색기 트리의 가상머신 가지(CB-Tumblebug 멀티클라우드)를 조립한다 (docs/06 §2, §4.2, §6.1, §7.1, §9.5~9.7).
 *
 * <pre>
 * 역할 → 가상머신 → Namespace → Infra → NodeGroup / 미분류 → Node(VM) → 모니터링 항목
 * </pre>
 *
 * <ul>
 *   <li>역할 스코프: {@code role_namespaces} (Role → Tumblebug nsId). 매핑된 Namespace 만 트리에 포함한다.</li>
 *   <li>조회: {@code GET /ns} 1회 + Namespace 별 {@code GET /ns/{nsId}/infra} 1회. 응답의 node[] 에
 *       nodeGroupId·status·connectionConfig.providerName 이 포함되어 NodeGroup·Node 개별 조회는 하지 않는다.</li>
 *   <li>캐시: 결과를 TTL(기본 1분) 동안 재사용하고, 갱신 실패 시 마지막 정상 결과를 유지하며 가상머신 노드에
 *       조회 실패 상태({@link #UNAVAILABLE_STATUS})를 표시한다.</li>
 *   <li>상태: Tumblebug 상태값을 변환 없이 노출하고 Terminated 도 제외하지 않는다.</li>
 *   <li>CSP: 트리에는 providerName 만 싣고 connectionName·Region 은 상세 API 로 미룬다. Infra 를 connection 별로 복제하지 않는다.</li>
 *   <li>지표: Tumblebug 자체 모니터링 API 는 쓰지 않고 Thanos 의 tb_ns/tb_infra/tb_node 라벨로 조인한다.</li>
 * </ul>
 *
 * 트리 항목 ID / resourceId 체계:
 * <pre>
 * vmroot:{role}                         | -
 * ns:{role}:{ns}                        | TB:{ns}
 * infra:{role}:{ns}:{infra}             | TB:{ns}/{infra}
 * ng:{role}:{ns}:{infra}:{group}        | TB:{ns}/{infra}/@{group}   (미분류: @unclassified)
 * tbvm:{role}:{ns}:{infra}:{node}       | TB:{ns}/{infra}/{node}
 * </pre>
 */
@Component
public class TumblebugTreeBuilder {

    private static final Logger log = LoggerFactory.getLogger(TumblebugTreeBuilder.class);

    /** nodeGroupId 가 없는 Node 를 담는 화면용 그룹 키 */
    public static final String UNCLASSIFIED_KEY = "@unclassified";
    /** Tumblebug 조회 실패 시 가상머신 루트 노드에 표시하는 상태 */
    public static final String UNAVAILABLE_STATUS = "TUMBLEBUG_UNAVAILABLE";

    /** Tumblebug NodeSummary 중 트리·패널에 필요한 필드 */
    public record TbNode(String id, String name, String status, String nodeGroupId, String provider) {}

    /** Tumblebug InfraInfoSummary 중 트리·패널에 필요한 필드 */
    public record TbInfra(String id, String name, String status, List<TbNode> nodes) {}

    /** 한 시점의 Tumblebug 자원 구조. degraded 는 마지막 갱신이 실패했음을 뜻한다. 패널 집계도 같은 스냅샷을 쓴다. */
    public record TbSnapshot(Instant loadedAt,
                      Map<String, String> namespaceNames,
                      Map<String, List<TbInfra>> infrasByNamespace,
                      boolean degraded) {

        static TbSnapshot empty(Instant at, boolean degraded) {
            return new TbSnapshot(at, Map.of(), Map.of(), degraded);
        }
    }

    private final TumblebugClient tumblebugClient;
    private final RoleNamespaceRepository roleNamespaceRepository;
    private final MonitoringCapabilityResolver capabilityResolver;
    private final ResourceExplorerProperties properties;
    private final Clock clock;

    private volatile TbSnapshot cached;

    @Autowired
    public TumblebugTreeBuilder(TumblebugClient tumblebugClient,
                                RoleNamespaceRepository roleNamespaceRepository,
                                MonitoringCapabilityResolver capabilityResolver,
                                ResourceExplorerProperties properties) {
        this(tumblebugClient, roleNamespaceRepository, capabilityResolver, properties, Clock.systemUTC());
    }

    public TumblebugTreeBuilder(TumblebugClient tumblebugClient,
                                RoleNamespaceRepository roleNamespaceRepository,
                                MonitoringCapabilityResolver capabilityResolver,
                                ResourceExplorerProperties properties,
                                Clock clock) {
        this.tumblebugClient = tumblebugClient;
        this.roleNamespaceRepository = roleNamespaceRepository;
        this.capabilityResolver = capabilityResolver;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 역할 이름별 '가상머신' 가지를 만든다. Namespace 매핑이 없는 역할은 결과에 포함하지 않는다.
     * 매핑은 있으나 Tumblebug 조회가 실패한 상태면 조회 실패 상태를 단 루트 노드를 포함한다.
     *
     * @return 역할 이름 → VM_ROOT 노드 (parentId 는 role:{role})
     */
    @Transactional(readOnly = true)
    public Map<String, ResourceTreeNodeResponse> build(Collection<String> roleNames) {
        if (roleNames.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> namespacesByRole = new LinkedHashMap<>();
        for (RoleNamespace mapping : roleNamespaceRepository.findByRole_NameIn(roleNames)) {
            namespacesByRole.computeIfAbsent(mapping.getRole().getName(), k -> new TreeSet<>()).add(mapping.getNsId());
        }
        if (namespacesByRole.isEmpty()) {
            return Map.of();
        }

        TbSnapshot tb = snapshot();
        Snapshot metrics = capabilityResolver.snapshot();

        Map<String, ResourceTreeNodeResponse> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : namespacesByRole.entrySet()) {
            String role = entry.getKey();
            String vmRootId = "vmroot:" + role;
            List<ResourceTreeNodeResponse> namespaces = entry.getValue().stream()
                    .filter(tb.namespaceNames()::containsKey)          // Tumblebug 에 없는 Namespace 는 건너뜀
                    .map(nsId -> namespaceNode(role, vmRootId, nsId, tb, metrics))
                    .toList();
            if (namespaces.isEmpty() && !tb.degraded()) {
                continue;
            }
            result.put(role, ResourceTreeNodeResponse.of(vmRootId, "role:" + role,
                    ResourceTreeNodeType.VM_ROOT.displayName(), ResourceTreeNodeType.VM_ROOT,
                    null, role, null, tb.degraded() ? UNAVAILABLE_STATUS : null, null, namespaces));
        }
        return result;
    }

    // ── 계층 조립 ───────────────────────────────────────────────

    private static ResourceTreeNodeResponse namespaceNode(String role,
                                                          String vmRootId,
                                                          String nsId,
                                                          TbSnapshot tb,
                                                          Snapshot metrics) {
        String nsNodeId = "ns:" + role + ":" + nsId;
        List<ResourceTreeNodeResponse> infras = tb.infrasByNamespace().getOrDefault(nsId, List.of()).stream()
                .sorted(Comparator.comparing(TbInfra::name, String.CASE_INSENSITIVE_ORDER))
                .map(infra -> infraNode(role, nsNodeId, nsId, infra, metrics))
                .toList();
        String name = tb.namespaceNames().getOrDefault(nsId, nsId);
        return ResourceTreeNodeResponse.of(nsNodeId, vmRootId, name, ResourceTreeNodeType.NAMESPACE,
                "TB:" + nsId, role, null, null, null, infras);
    }

    private static ResourceTreeNodeResponse infraNode(String role,
                                                      String nsNodeId,
                                                      String nsId,
                                                      TbInfra infra,
                                                      Snapshot metrics) {
        String infraNodeId = "infra:" + role + ":" + nsId + ":" + infra.id();
        String infraResourceId = "TB:" + nsId + "/" + infra.id();

        // NodeGroup 은 이름순, 미분류(nodeGroupId 없음)는 마지막
        Map<String, List<TbNode>> byGroup = new TreeMap<>();
        List<TbNode> unclassified = new ArrayList<>();
        for (TbNode node : infra.nodes()) {
            if (node.nodeGroupId() == null || node.nodeGroupId().isBlank()) {
                unclassified.add(node);
            } else {
                byGroup.computeIfAbsent(node.nodeGroupId(), k -> new ArrayList<>()).add(node);
            }
        }
        List<ResourceTreeNodeResponse> groups = new ArrayList<>();
        byGroup.forEach((groupId, nodes) -> groups.add(groupNode(role, infraNodeId, infraResourceId, nsId, infra.id(),
                groupId, ResourceTreeNodeType.NODE_GROUP, groupId, nodes, metrics)));
        if (!unclassified.isEmpty()) {
            groups.add(groupNode(role, infraNodeId, infraResourceId, nsId, infra.id(),
                    UNCLASSIFIED_KEY, ResourceTreeNodeType.UNCLASSIFIED, ResourceTreeNodeType.UNCLASSIFIED.displayName(),
                    unclassified, metrics));
        }
        return ResourceTreeNodeResponse.of(infraNodeId, nsNodeId, infra.name(), ResourceTreeNodeType.INFRA,
                infraResourceId, role, null, infra.status(), null, groups);
    }

    private static ResourceTreeNodeResponse groupNode(String role,
                                                      String infraNodeId,
                                                      String infraResourceId,
                                                      String nsId,
                                                      String infraId,
                                                      String groupKey,
                                                      ResourceTreeNodeType type,
                                                      String name,
                                                      List<TbNode> nodes,
                                                      Snapshot metrics) {
        String groupNodeId = "ng:" + role + ":" + nsId + ":" + infraId + ":" + groupKey;
        String groupResourceId = infraResourceId + "/" + (UNCLASSIFIED_KEY.equals(groupKey) ? UNCLASSIFIED_KEY : "@" + groupKey);
        List<ResourceTreeNodeResponse> vms = nodes.stream()
                .sorted(Comparator.comparing(TbNode::name, String.CASE_INSENSITIVE_ORDER))
                .map(node -> vmNode(role, groupNodeId, nsId, infraId, node, metrics))
                .toList();
        return ResourceTreeNodeResponse.of(groupNodeId, infraNodeId, name, type, groupResourceId, role, null,
                null, null, vms);
    }

    private static ResourceTreeNodeResponse vmNode(String role,
                                                   String groupNodeId,
                                                   String nsId,
                                                   String infraId,
                                                   TbNode node,
                                                   Snapshot metrics) {
        String vmNodeId = "tbvm:" + role + ":" + nsId + ":" + infraId + ":" + node.id();
        MonitoringStatus status = metrics.tbStatusOf(nsId, infraId, node.id());

        List<ResourceTreeNodeResponse> items = new ArrayList<>();
        if (status != MonitoringStatus.NOT_CONFIGURED) {
            items.addAll(PhysicalTreeBuilder.nodeExporterItems(vmNodeId, role, null, status));
            List<GpuDevice> gpus = metrics.tbGpuDevices(nsId, infraId, node.id());
            ResourceTreeNodeResponse gpuGroup = PhysicalTreeBuilder.gpuGroup(vmNodeId, role, null, gpus, status);
            if (gpuGroup != null) {
                items.add(gpuGroup);
            }
        }
        return ResourceTreeNodeResponse.of(vmNodeId, groupNodeId, node.name(), ResourceTreeNodeType.VIRTUAL_MACHINE,
                "TB:" + nsId + "/" + infraId + "/" + node.id(), role, null, node.provider(), node.status(), status, items);
    }

    // ── Tumblebug 조회·캐시 ─────────────────────────────────────

    /** 캐시가 유효하면 재사용하고, 만료됐으면 Tumblebug 을 다시 조회한다. 트리와 패널이 같은 스냅샷을 공유한다. */
    public TbSnapshot snapshot() {
        TbSnapshot current = cached;
        if (isFresh(current)) {
            return current;
        }
        synchronized (this) {
            current = cached;
            if (isFresh(current)) {
                return current;
            }
            TbSnapshot loaded = load(current);
            cached = loaded;
            return loaded;
        }
    }

    /** 캐시를 비워 다음 조회에서 Tumblebug 을 다시 읽게 한다. */
    public void evict() {
        cached = null;
    }

    private boolean isFresh(TbSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        Duration ttl = properties.getTumblebugCacheTtl();
        return snapshot.loadedAt().plus(ttl).isAfter(clock.instant());
    }

    private TbSnapshot load(TbSnapshot previous) {
        Instant now = clock.instant();
        try {
            Map<String, String> names = new LinkedHashMap<>();
            for (Map<String, Object> ns : listOfMaps(tumblebugClient.listNamespaces(), "ns")) {
                String id = str(ns, "id");
                if (id != null) {
                    names.put(id, str(ns, "name") == null ? id : str(ns, "name"));
                }
            }
            Map<String, List<TbInfra>> infras = new LinkedHashMap<>();
            for (String nsId : names.keySet()) {
                List<TbInfra> list = new ArrayList<>();
                for (Map<String, Object> infra : listOfMaps(tumblebugClient.listInfra(nsId), "infra")) {
                    String infraId = str(infra, "id");
                    if (infraId == null) {
                        continue;
                    }
                    List<TbNode> nodes = new ArrayList<>();
                    for (Map<String, Object> node : listOfMaps(infra, "node")) {
                        String nodeId = str(node, "id");
                        if (nodeId == null) {
                            continue;
                        }
                        nodes.add(new TbNode(nodeId, str(node, "name") == null ? nodeId : str(node, "name"),
                                str(node, "status"), str(node, "nodeGroupId"),
                                str(nested(node, "connectionConfig"), "providerName")));
                    }
                    list.add(new TbInfra(infraId, str(infra, "name") == null ? infraId : str(infra, "name"),
                            str(infra, "status"), nodes));
                }
                infras.put(nsId, list);
            }
            return new TbSnapshot(now, names, infras, false);
        } catch (RuntimeException e) {
            if (previous != null) {
                log.warn("Tumblebug 자원 구조 조회 실패 — 마지막 정상 결과를 유지합니다: {}", e.getMessage());
                return new TbSnapshot(now, previous.namespaceNames(), previous.infrasByNamespace(), true);
            }
            log.warn("Tumblebug 자원 구조 조회 실패 — 가상머신 가지를 조회 실패 상태로 표시합니다: {}", e.getMessage());
            return TbSnapshot.empty(now, true);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Map<String, Object> source, String key) {
        if (source == null) {
            return List.of();
        }
        Object value = source.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String str(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isBlank() ? null : s;
    }
}

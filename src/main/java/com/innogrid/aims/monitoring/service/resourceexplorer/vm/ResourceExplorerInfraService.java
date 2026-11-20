package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.infra.InfraSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.infra.NodeGroupListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeType;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 Infra 패널 조회 서비스 — 트리의 Tumblebug Infra 선택 시 개요 탭의 상태 카드와 NodeGroup·미분류 목록.
 *
 * <ul>
 *   <li>스코프: nsId 가 토큰 역할(roleName 지정 시 그 역할)의 role_namespaces 매핑에 있고, 스냅샷에 Infra 가 있어야 한다. 아니면 404.</li>
 *   <li>상태 카드: Infra 집계 상태 원문은 그대로 싣고, 카운트는 Infra 의 Node(VM) 상태를 §6.1 버킷으로 묶는다
 *       (Tumblebug statusCount 와 같은 근거를 버킷 단위로 낸 값).</li>
 *   <li>NodeGroup 목록: 트리와 같은 순서(NodeGroup 이름순, 미분류는 VM 이 있을 때만 마지막).</li>
 * </ul>
 */
@Service
public class ResourceExplorerInfraService {

    private final TumblebugPanelSupport support;

    public ResourceExplorerInfraService(TumblebugPanelSupport support) {
        this.support = support;
    }

    /** Infra 개요 — 상태 원문, VM 수 / Running / Pending / Stop / Error, NodeGroup 수, 미분류 VM 수 */
    public InfraSummaryResponse getInfraSummary(String roleName, String nsId, String infraId) {
        TbInfra infra = TumblebugPanelSupport.infraOf(support.accessibleNamespace(roleName, nsId), nsId, infraId);
        List<ResourceStatus> statuses = TumblebugPanelSupport.statusesOf(infra.nodes());
        long unclassified = infra.nodes().stream().filter(TumblebugPanelSupport::isUnclassified).count();
        long groups = infra.nodes().stream()
                .filter(n -> !TumblebugPanelSupport.isUnclassified(n))
                .map(TbNode::nodeGroupId)
                .distinct()
                .count();
        return new InfraSummaryResponse(
                infra.id(),
                infra.name(),
                infra.status(),
                statuses.size(),
                TumblebugPanelSupport.count(statuses, ResourceStatus.RUNNING),
                TumblebugPanelSupport.count(statuses, ResourceStatus.PENDING),
                TumblebugPanelSupport.count(statuses, ResourceStatus.STOP),
                TumblebugPanelSupport.count(statuses, ResourceStatus.ERROR),
                groups,
                unclassified);
    }

    /** NodeGroup·미분류 목록 — 이름순, 미분류(VM 이 있을 때만)는 마지막 */
    public List<NodeGroupListItemResponse> getInfraNodeGroups(String roleName, String nsId, String infraId) {
        TbInfra infra = TumblebugPanelSupport.infraOf(support.accessibleNamespace(roleName, nsId), nsId, infraId);

        Map<String, List<TbNode>> byGroup = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<TbNode> unclassified = new ArrayList<>();
        for (TbNode node : infra.nodes()) {
            if (TumblebugPanelSupport.isUnclassified(node)) {
                unclassified.add(node);
            } else {
                byGroup.computeIfAbsent(node.nodeGroupId(), k -> new ArrayList<>()).add(node);
            }
        }

        List<NodeGroupListItemResponse> rows = new ArrayList<>();
        byGroup.forEach((groupId, nodes) -> rows.add(new NodeGroupListItemResponse(
                groupId, groupId, false, nodes.size(), TumblebugPanelSupport.summaryOf(nodes))));
        if (!unclassified.isEmpty()) {
            rows.add(new NodeGroupListItemResponse(
                    TumblebugTreeBuilder.UNCLASSIFIED_KEY, ResourceTreeNodeType.UNCLASSIFIED.displayName(), true,
                    unclassified.size(), TumblebugPanelSupport.summaryOf(unclassified)));
        }
        return rows;
    }
}

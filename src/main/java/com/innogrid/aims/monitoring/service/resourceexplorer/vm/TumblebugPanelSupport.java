package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusSummary;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugStatusMapper;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import org.springframework.stereotype.Component;

/**
 * Namespace·Infra 패널 공통 — Namespace 접근 검증과 Tumblebug 스냅샷 조회, VM 상태 집계.
 *
 * 접근 규칙은 트리와 같다: nsId 가 토큰 역할(roleName 지정 시 그 역할)의 role_namespaces 매핑에 있어야 하며,
 * 아니면 404 다. 스냅샷은 요청 Namespace 1개만 조회한다({@link TumblebugTreeBuilder#snapshot}, 캐시 1분).
 */
@Component
public class TumblebugPanelSupport {

    private final AuthService authService;
    private final RoleNamespaceRepository roleNamespaceRepository;
    private final TumblebugTreeBuilder tumblebugTreeBuilder;

    public TumblebugPanelSupport(AuthService authService,
                                 RoleNamespaceRepository roleNamespaceRepository,
                                 TumblebugTreeBuilder tumblebugTreeBuilder) {
        this.authService = authService;
        this.roleNamespaceRepository = roleNamespaceRepository;
        this.tumblebugTreeBuilder = tumblebugTreeBuilder;
    }

    /** 스코프 검증을 통과한 Namespace 의 Tumblebug 스냅샷 — 매핑되지 않은 Namespace 면 404 */
    public TbSnapshot accessibleNamespace(String roleName, String nsId) {
        Set<String> userRoles = authService.currentUserRoleIds();
        List<String> roles = roleName == null ? new ArrayList<>(userRoles)
                : userRoles.contains(roleName) ? List.of(roleName) : List.of();
        boolean mapped = !roles.isEmpty() && roleNamespaceRepository.findByRole_NameIn(roles).stream()
                .map(RoleNamespace::getNsId)
                .anyMatch(nsId::equals);
        if (!mapped) {
            throw BusinessException.notFound("Namespace 를 찾을 수 없습니다: " + nsId);
        }
        return tumblebugTreeBuilder.snapshot(Set.of(nsId));
    }

    /** 역할 1개에 매핑된 Namespace 전체와 그 Tumblebug 스냅샷 — 가상머신 그룹 루트(VM_ROOT) 패널용 */
    public record RoleNamespaces(List<String> nsIds, TbSnapshot snapshot) {}

    /**
     * roleName 이 현재 사용자 토큰의 역할일 때 그 역할의 role_namespaces 매핑 전체(nsId 오름차순)와 스냅샷을 돌려준다.
     * 토큰에 없는 역할이면 404. 매핑이 없으면 빈 목록과 빈 스냅샷(Tumblebug 미조회).
     */
    public RoleNamespaces accessibleRoleNamespaces(String roleName) {
        if (!authService.currentUserRoleIds().contains(roleName)) {
            throw BusinessException.notFound("역할을 찾을 수 없습니다: " + roleName);
        }
        List<String> nsIds = roleNamespaceRepository.findByRole_NameIn(List.of(roleName)).stream()
                .map(RoleNamespace::getNsId)
                .distinct()
                .sorted()
                .toList();
        if (nsIds.isEmpty()) {
            return new RoleNamespaces(List.of(), new TbSnapshot(Instant.now(), Map.of(), Map.of(), false));
        }
        return new RoleNamespaces(nsIds, tumblebugTreeBuilder.snapshot(Set.copyOf(nsIds)));
    }

    /** 스냅샷에서 Infra 1개 — 없으면 404 */
    public static TbInfra infraOf(TbSnapshot snapshot, String nsId, String infraId) {
        return snapshot.infrasByNamespace().getOrDefault(nsId, List.of()).stream()
                .filter(i -> i.id().equals(infraId))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("Infra 를 찾을 수 없습니다: " + infraId));
    }

    /**
     * 스코프 검증을 통과한 Namespace 의 스냅샷에서 Node(VM) 1개 — Namespace 미매핑·Infra/Node 부재면 404.
     * vmId 는 Tumblebug node id(트리 VIRTUAL_MACHINE 노드의 vmId 필드)다. 같은 Infra 안에서 유일하므로 nodeGroupId 는 필요 없다.
     */
    public TbNode accessibleNode(String roleName, String nsId, String infraId, String vmId) {
        TbSnapshot snapshot = accessibleNamespace(roleName, nsId);
        return snapshot.infrasByNamespace().getOrDefault(nsId, List.of()).stream()
                .filter(i -> i.id().equals(infraId))
                .map(TbInfra::nodes)
                .flatMap(List::stream)
                .filter(n -> n.id().equals(vmId))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("가상머신을 찾을 수 없습니다: " + vmId));
    }

    /** 스코프 검증(404) 뒤 VM 1대의 지표 선택자 — tb_ns·tb_infra·tb_node 라벨 */
    public MetricSelector selector(String roleName, String nsId, String infraId, String vmId) {
        accessibleNode(roleName, nsId, infraId, vmId);
        return MetricSelector.tumblebugNode(nsId, infraId, vmId);
    }

    /** Node(VM) 목록의 §6.1 상태 버킷 */
    public static List<ResourceStatus> statusesOf(List<TbNode> nodes) {
        return nodes.stream()
                .map(n -> ResourceStatus.valueOf(TumblebugStatusMapper.bucket(n.status()).name()))
                .toList();
    }

    /** Node(VM) 목록의 상태 스택바 */
    public static ResourceStatusSummary summaryOf(List<TbNode> nodes) {
        return ResourceStatusSummary.of(statusesOf(nodes));
    }

    public static long count(List<ResourceStatus> statuses, ResourceStatus status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    /** nodeGroupId 가 비어 있으면 미분류 */
    public static boolean isUnclassified(TbNode node) {
        return node.nodeGroupId() == null || node.nodeGroupId().isBlank();
    }
}

package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.mock.MockDataProvider;
import com.innogrid.aims.monitoring.ResourceExplorerProperties;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeFilter;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeType;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreePathResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeSearchResponse;
import com.innogrid.aims.monitoring.model.ResourceExplorerTreeDataset;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 왼쪽 트리 구성 서비스.
 *
 * 역할(ROLE)은 요청 파라미터가 아니라 현재 사용자 토큰(SecurityContext)의 권한에서 추출한다 —
 * 사용자는 자신에게 부여된 역할의 자원 트리만 볼 수 있다.
 *
 * 트리 항목 ID 체계 (getChildren 이 이 규칙으로 하위를 해석한다):
 *   root | role:{roleId} | nodes:{roleId}:{deploymentId} | vms:{roleId}:{deploymentId}
 *   host:{nodeId} | vm:{vmId} | cat:{ownerId}:{TYPE} | gpus:{ownerId} | gpu:{ownerId}:{gpuId}
 *
 * 노드그룹 패널(개요 KPI·Top-N·목록)은 ResourceExplorerNodeGroupService 가 담당한다.
 */
@Service
public class ResourceExplorerTreeService {

    // TODO:: 자원 관리 데이터 연동 시 Mock 플래그 의존성 제거
    private final ResourceExplorerProperties properties;
    // TODO:: 자원 관리 데이터 연동 시 MockDataProvider 의존성을 실제 원장 조회로 교체
    private final MockDataProvider mockDataProvider;

    public ResourceExplorerTreeService(ResourceExplorerProperties properties,
                                       MockDataProvider mockDataProvider) {
        this.properties = properties;
        this.mockDataProvider = mockDataProvider;
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** ROOT부터 모든 하위 계층을 포함한 전체 트리를 구성한다. 역할은 사용자 토큰 파싱 값 기준. */
    public ResourceTreeNodeResponse getTree(ResourceTreeFilter filter) {
        ResourceExplorerTreeDataset dataset = loadDataset();
        Set<String> userRoles = currentUserRoleIds();

        List<ResourceTreeNodeResponse> roles = dataset.roles().stream()
                .filter(r -> userRoles.contains(r.roleId()))
                .map(r -> roleSubtree(dataset, r, filter))
                .filter(Objects::nonNull)
                .toList();
        ResourceTreeNodeResponse root = new ResourceTreeNodeResponse(
                "root",
                null, ResourceTreeNodeType.ROOT.displayName(),
                ResourceTreeNodeType.ROOT,
                null,
                null,
                null,
                null,
                roles.isEmpty(),
                roles.size(),
                roles);
        // 검색 조건이 존재 하지 않을 경우 반환
        if (filter.keyword() == null) {
            return root;
        }
        return Objects.requireNonNullElse(
                prune(root, filter.keyword().toLowerCase(Locale.ROOT)),
                new ResourceTreeNodeResponse(
                        "root",
                        null,
                        ResourceTreeNodeType.ROOT.displayName(),
                        ResourceTreeNodeType.ROOT,
                        null,
                        null,
                        null,
                        null,
                        true,
                        0,
                        List.of()));
    }

    /** Lazy Loading을 위한 ROLE 계층을 조회한다. */
    public List<ResourceTreeNodeResponse> getRoots(ResourceTreeFilter filter) {
        return getTree(filter).children().stream()
                .map(ResourceExplorerTreeService::withoutChildren)
                .toList();
    }

    /** 선택된 항목 유형에 따라 직계 하위 계층만 조회한다. */
    public List<ResourceTreeNodeResponse> getChildren(String treeNodeId) {
        ResourceTreeNodeResponse node = findNode(treeNodeId);
        List<ResourceTreeNodeResponse> children = node.children() == null ? List.of() : node.children();
        return children.stream().map(ResourceExplorerTreeService::withoutChildren).toList();
    }

    /** 이름으로 자원을 검색하고 ROOT부터 검색 결과까지의 경로를 반환한다. */
    public List<ResourceTreeSearchResponse> search(String keyword, List<ResourceTreeNodeType> types, int limit) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        List<ResourceTreeSearchResponse> results = new ArrayList<>();
        collectMatches(getTree(new ResourceTreeFilter(null, null, null)),
                new ArrayList<>(), lower, types, limit, results);
        return results;
    }

    // ── 사용자 역할 추출 ────────────────────────────────────────

    /** 현재 사용자 토큰의 권한(ROLE_*)에서 역할 ID 를 추출한다. 미인증이면 빈 집합.
     *  TODO:: AuthService 로 위치 변경 필요
     * */
    private Set<String> currentUserRoleIds() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                roles.add(name.substring("ROLE_".length()));
            }
        }
        return roles;
    }

    // ── 트리 구성 내부 ──────────────────────────────────────────

    private ResourceExplorerTreeDataset loadDataset() {
        // TODO:: 자원 관리 데이터(역할·노드·VM 원장) 연동 시 Mock 분기를 실제 조회로 교체
        if (properties.isMockEnabled()) {
            return mockDataProvider.getResourceExplorerTreeDataset();
        }
        throw new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "자원 관리 데이터와 자원 탐색기 트리의 매핑 구현 전입니다.");
    }

    private ResourceTreeNodeResponse roleSubtree(ResourceExplorerTreeDataset dataset, ResourceExplorerTreeDataset.RoleInfo role, ResourceTreeFilter filter) {
        Set<String> deployments = new LinkedHashSet<>();
        dataset.nodes().stream()
                .filter(n -> n.roleId().equals(role.roleId()))
                .forEach(n -> deployments.add(n.deploymentId()));
        dataset.vms().stream()
                .filter(v -> v.roleId().equals(role.roleId()))
                .forEach(v -> deployments.add(v.deploymentId()));

        String roleNodeId = "role:" + role.roleId();
        List<ResourceTreeNodeResponse> groups = new ArrayList<>();
        for (String deploymentId : deployments) {
            if (!filter.deploymentIds().isEmpty() && !filter.deploymentIds().contains(deploymentId)) {
                continue;
            }
            List<ResourceTreeNodeResponse> hosts = dataset.nodes().stream()
                    .filter(n -> n.roleId().equals(role.roleId()) && n.deploymentId().equals(deploymentId))
                    .map(n -> hostSubtree(n, role.roleId(), deploymentId))
                    .toList();
            if (!hosts.isEmpty()) {
                String groupId = "nodes:" + role.roleId() + ":" + deploymentId;
                groups.add(new ResourceTreeNodeResponse(
                        groupId,
                        roleNodeId,
                        "노드 (배포영역: " + deploymentId + ")",
                        ResourceTreeNodeType.PHYSICAL_HOST_GROUP,
                        null,
                        role.roleId(),
                        deploymentId,
                        null,
                        false,
                        hosts.size(),
                        hosts));
            }
            List<ResourceTreeNodeResponse> vms = dataset.vms().stream()
                    .filter(v -> v.roleId().equals(role.roleId()) && v.deploymentId().equals(deploymentId))
                    .map(v -> vmSubtree(v, role.roleId(), deploymentId))
                    .toList();
            if (!vms.isEmpty()) {
                String groupId = "vms:" + role.roleId() + ":" + deploymentId;
                groups.add(new ResourceTreeNodeResponse(
                        groupId,
                        roleNodeId,
                        "가상머신 (배포영역: " + deploymentId + ")", ResourceTreeNodeType.VM_GROUP,
                        null,
                        role.roleId(),
                        deploymentId,
                        null,
                        false,
                        vms.size(),
                        vms));
            }
        }
        if (groups.isEmpty()) {
            return null;
        }
        return new ResourceTreeNodeResponse(
                roleNodeId,
                "root",
                role.roleName(),
                ResourceTreeNodeType.ROLE,
                role.roleId(),
                role.roleId(),
                null,
                null,
                false,
                groups.size(),
                groups);
    }

    private ResourceTreeNodeResponse hostSubtree(ResourceExplorerTreeDataset.NodeInfo node, String roleId, String deploymentId) {
        String hostNodeId = "host:" + node.nodeId();
        List<ResourceTreeNodeResponse> children = categoryChildren(hostNodeId, ResourceTreeNodeType.HOST_CATEGORIES, roleId, deploymentId, node.gpuIds());
        return new ResourceTreeNodeResponse(
                hostNodeId,
                "nodes:" + roleId + ":" + deploymentId,
                node.name(),
                ResourceTreeNodeType.PHYSICAL_HOST,
                node.nodeId(),
                roleId,
                deploymentId,
                node.status(),
                false,
                children.size(),
                children);
    }

    private ResourceTreeNodeResponse vmSubtree(ResourceExplorerTreeDataset.VmInfo vm, String roleId, String deploymentId) {
        String vmNodeId = "vm:" + vm.vmId();
        List<ResourceTreeNodeResponse> children = categoryChildren(vmNodeId, ResourceTreeNodeType.VM_CATEGORIES, roleId, deploymentId, vm.gpuIds());
        return new ResourceTreeNodeResponse(
                vmNodeId,
                "vms:" + roleId + ":" + deploymentId,
                vm.name(),
                ResourceTreeNodeType.VIRTUAL_MACHINE,
                vm.vmId(),
                roleId,
                deploymentId,
                vm.status(),
                false,
                children.size(),
                children);
    }

    private List<ResourceTreeNodeResponse> categoryChildren(String ownerId,
                                                            List<ResourceTreeNodeType> categories,
                                                            String roleId,
                                                            String deploymentId,
                                                            List<String> gpuIds) {
        List<ResourceTreeNodeResponse> children = new ArrayList<>();
        categories.forEach(type -> children.add(new ResourceTreeNodeResponse(
                "cat:" + ownerId + ":" + type,
                ownerId,
                type.displayName(),
                type,
                null,
                roleId,
                deploymentId,
                null,
                true,
                0,
                List.of())));
        if (!gpuIds.isEmpty()) {
            String gpuGroupId = "gpus:" + ownerId;
            List<ResourceTreeNodeResponse> gpus = gpuIds.stream()
                    .map(gpuId -> new ResourceTreeNodeResponse(
                            "gpu:" + ownerId + ":" + gpuId, gpuGroupId,
                            gpuId.toUpperCase(Locale.ROOT).replace("-", " "),
                            ResourceTreeNodeType.GPU_DEVICE,
                            gpuId,
                            roleId,
                            deploymentId,
                            null,
                            true,
                            0,
                            List.of()))
                    .toList();

            children.add(new ResourceTreeNodeResponse(
                    gpuGroupId,
                    ownerId,
                    ResourceTreeNodeType.GPU_GROUP.displayName(),
                    ResourceTreeNodeType.GPU_GROUP,
                    null,
                    roleId,
                    deploymentId,
                    null,
                    false,
                    gpus.size(),
                    gpus));
        }
        return children;
    }

    /** keyword 와 이름이 일치하는 항목(또는 그 하위가 일치하는 항목)만 남긴다. */
    private static ResourceTreeNodeResponse prune(ResourceTreeNodeResponse node, String lowerKeyword) {
        boolean selfMatch = node.name().toLowerCase(Locale.ROOT).contains(lowerKeyword);
        List<ResourceTreeNodeResponse> children = node.children() == null ? List.of() : node.children();
        List<ResourceTreeNodeResponse> survived = children.stream()
                .map(c -> prune(c, lowerKeyword))
                .filter(Objects::nonNull)
                .toList();
        if (!selfMatch && survived.isEmpty()) {
            return null;
        }
        // 자기 자신이 일치하면 하위는 원본 그대로 유지한다 (검색된 노드를 펼쳐볼 수 있게)
        List<ResourceTreeNodeResponse> effective = selfMatch && survived.isEmpty() ? children : survived;
        return new ResourceTreeNodeResponse(
                node.id(),
                node.parentId(),
                node.name(),
                node.type(),
                node.resourceId(),
                node.roleId(),
                node.deploymentId(),
                node.status(),
                effective.isEmpty(),
                effective.size(),
                effective);
    }

    private ResourceTreeNodeResponse findNode(String treeNodeId) {
        ResourceTreeNodeResponse found = findNode(
                getTree(new ResourceTreeFilter(null, null, null)), treeNodeId);
        if (found == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "트리 항목을 찾을 수 없습니다: " + treeNodeId);
        }
        return found;
    }

    private static ResourceTreeNodeResponse findNode(ResourceTreeNodeResponse node, String treeNodeId) {
        if (node.id().equals(treeNodeId)) {
            return node;
        }
        if (node.children() == null) {
            return null;
        }
        for (ResourceTreeNodeResponse child : node.children()) {
            ResourceTreeNodeResponse found = findNode(child, treeNodeId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void collectMatches(ResourceTreeNodeResponse node,
                                       List<ResourceTreePathResponse> path,
                                       String lowerKeyword,
                                       List<ResourceTreeNodeType> types,
                                       int limit,
                                       List<ResourceTreeSearchResponse> results) {
        if (results.size() >= limit) {
            return;
        }
        boolean typeOk = types == null || types.isEmpty() || types.contains(node.type());
        if (node.type() != ResourceTreeNodeType.ROOT && typeOk && node.name().toLowerCase(Locale.ROOT).contains(lowerKeyword)) {
            results.add(new ResourceTreeSearchResponse(withoutChildren(node), List.copyOf(path)));
        }
        if (node.children() == null) {
            return;
        }
        path.add(new ResourceTreePathResponse(node.id(), node.name(), node.type()));
        for (ResourceTreeNodeResponse child : node.children()) {
            collectMatches(child, path, lowerKeyword, types, limit, results);
        }
        path.remove(path.size() - 1);
    }

    private static ResourceTreeNodeResponse withoutChildren(ResourceTreeNodeResponse node) {
        return new ResourceTreeNodeResponse(
                node.id(),
                node.parentId(),
                node.name(),
                node.type(),
                node.resourceId(),
                node.roleId(),
                node.deploymentId(),
                node.status(),
                node.leaf(),
                node.childCount(),
                null);
    }
}

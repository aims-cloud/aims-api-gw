package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeFilter;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeType;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreePathResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeSearchResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.PhysicalTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 왼쪽 트리 구성 서비스 (docs/06 자원 탐색기 트리 설계).
 *
 * 역할(ROLE)은 요청 파라미터가 아니라 현재 사용자 토큰(SecurityContext)의 권한에서 추출한다 —
 * 사용자는 자신에게 부여된 역할에 매핑된 자원 트리만 볼 수 있다.
 *
 * 트리 항목 ID 체계:
 *   root | role:{role}
 *   physical:{role} → PhysicalTreeBuilder 의 하위 체계 (MDC → 물리 호스트 → 항목·가상자원)
 *   vmroot:{role}   → TumblebugTreeBuilder 의 하위 체계 (Namespace → Infra → NodeGroup/미분류 → Node)
 *
 * 화면 최초 로딩은 getTree 로 전체 계층을 한 번에 받고, roots/children 은 Lazy Loading 화면용 보조 API 다.
 */
@Service
public class ResourceExplorerTreeService {

    private static final String ROOT_ID = "root";

    private final PhysicalTreeBuilder physicalTreeBuilder;
    private final TumblebugTreeBuilder tumblebugTreeBuilder;
    private final AuthService authService;

    public ResourceExplorerTreeService(PhysicalTreeBuilder physicalTreeBuilder,
                                       TumblebugTreeBuilder tumblebugTreeBuilder,
                                       AuthService authService) {
        this.physicalTreeBuilder = physicalTreeBuilder;
        this.tumblebugTreeBuilder = tumblebugTreeBuilder;
        this.authService = authService;
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** ROOT부터 모니터링 항목까지 전체 트리를 구성한다. 역할은 사용자 토큰 기준이며 자원이 없어도 역할 노드는 항상 포함한다. */
    public ResourceTreeNodeResponse getTree(ResourceTreeFilter filter) {
        Set<String> userRoles = authService.currentUserRoleIds();
        Set<String> mdcFilter = new LinkedHashSet<>(filter.mdcIds());

        Map<String, ResourceTreeNodeResponse> physicalByRole = physicalTreeBuilder.build(userRoles, mdcFilter);
        // MDC 필터는 물리 가지 전용 — 가상머신 가지는 필터가 걸려 있으면 생략한다
        Map<String, ResourceTreeNodeResponse> vmByRole = mdcFilter.isEmpty()
                ? tumblebugTreeBuilder.build(userRoles) : Map.of();

        List<ResourceTreeNodeResponse> roles = userRoles.stream()
                .map(role -> roleNode(role, physicalByRole.get(role), vmByRole.get(role)))
                .toList();
        ResourceTreeNodeResponse root = rootNode(roles);

        if (filter.keyword() == null) {
            return root;
        }
        return Objects.requireNonNullElse(
                prune(root, filter.keyword().toLowerCase(Locale.ROOT)),
                rootNode(List.of()));
    }

    /** Lazy Loading을 위한 ROLE 계층을 조회한다. */
    public List<ResourceTreeNodeResponse> getRoots(ResourceTreeFilter filter) {
        return getTree(filter).children().stream()
                .map(ResourceTreeNodeResponse::withoutChildren)
                .toList();
    }

    /** 선택된 항목의 직계 하위 계층만 조회한다. */
    public List<ResourceTreeNodeResponse> getChildren(String treeNodeId) {
        ResourceTreeNodeResponse node = findNode(treeNodeId);
        List<ResourceTreeNodeResponse> children = node.children() == null ? List.of() : node.children();
        return children.stream().map(ResourceTreeNodeResponse::withoutChildren).toList();
    }

    /** 이름으로 자원을 검색하고 ROOT부터 검색 결과까지의 경로를 반환한다. */
    public List<ResourceTreeSearchResponse> search(String keyword, List<ResourceTreeNodeType> types, int limit) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        List<ResourceTreeSearchResponse> results = new ArrayList<>();
        collectMatches(getTree(new ResourceTreeFilter(null, null, null)),
                new ArrayList<>(), lower, types, limit, results);
        return results;
    }

    // ── 트리 구성 내부 ──────────────────────────────────────────

    private static ResourceTreeNodeResponse rootNode(List<ResourceTreeNodeResponse> roles) {
        return ResourceTreeNodeResponse.of(ROOT_ID, null, ResourceTreeNodeType.ROOT.displayName(),
                ResourceTreeNodeType.ROOT, null, null, null, null, null, roles);
    }

    /**
     * 역할 노드 — 역할은 자원이 아니라 사용자의 접근 범위이므로 매핑된 자원이 없어도 항상 노출한다
     * (leaf=true, childCount=0). 자원 스코프 필터링은 하위 가지 조립기가 담당한다.
     */
    private static ResourceTreeNodeResponse roleNode(String role,
                                                     ResourceTreeNodeResponse physical,
                                                     ResourceTreeNodeResponse virtualMachines) {
        List<ResourceTreeNodeResponse> branches = new ArrayList<>();
        if (physical != null) {
            branches.add(physical);
        }
        if (virtualMachines != null) {
            branches.add(virtualMachines);
        }
        return ResourceTreeNodeResponse.of("role:" + role, ROOT_ID, role, ResourceTreeNodeType.ROLE,
                role, role, null, null, null, branches);
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
        return node.withChildren(effective);
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
        if (node.type() != ResourceTreeNodeType.ROOT && typeOk
                && node.name().toLowerCase(Locale.ROOT).contains(lowerKeyword)) {
            results.add(new ResourceTreeSearchResponse(node.withoutChildren(), List.copyOf(path)));
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
}

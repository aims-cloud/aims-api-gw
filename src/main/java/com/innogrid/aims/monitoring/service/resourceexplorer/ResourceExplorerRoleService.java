package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.mock.MockDataProvider;
import com.innogrid.aims.monitoring.ResourceExplorerProperties;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse.StatusCount;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleDeploymentListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleDeploymentListItemResponse.StatusSummary;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleSummaryResponse;
import com.innogrid.aims.monitoring.model.ResourceExplorerTreeDataset;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 역할 패널 조회 서비스.
 * 트리에서 역할 선택 시 KPI 카드 8종, 노드/가상머신 상태 위젯, 배포영역 목록을 제공한다.
 *
 * 경로의 roleId 가 현재 사용자 토큰에 부여된 역할이 아니면 404 를 반환한다 —
 * 트리에 노출되지 않는 역할의 패널을 URL 로 직접 조회할 수 없다(트리·전체 자원 패널과 동일 스코프).
 * 왼쪽 트리는 ResourceExplorerTreeService, 노드그룹/VM그룹 패널은 각 그룹 서비스가 담당한다.
 */
@Service
public class ResourceExplorerRoleService {

    // TODO:: 자원 관리 데이터 연동 시 Mock 플래그 의존성 제거
    private final ResourceExplorerProperties properties;
    // TODO:: 자원 관리 데이터 연동 시 MockDataProvider 의존성을 실제 원장 조회로 교체
    private final MockDataProvider mockDataProvider;
    // TODO:: AuthService 로 공통화
    private final ResourceExplorerTreeService resourceExplorerTreeService;

    public ResourceExplorerRoleService(ResourceExplorerProperties properties, MockDataProvider mockDataProvider, ResourceExplorerTreeService resourceExplorerTreeService) {
        this.properties = properties;
        this.mockDataProvider = mockDataProvider;
        this.resourceExplorerTreeService = resourceExplorerTreeService;
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** KPI 카드 8종 — 멤버/배포영역/노드/가상머신 수, Running/Pending/Stop/Error */
    public RoleSummaryResponse getRoleSummary(String roleId) {
        ResourceExplorerTreeDataset dataset = loadDataset();
        ResourceExplorerTreeDataset.RoleInfo role = accessibleRole(dataset, roleId);

        List<ResourceExplorerTreeDataset.NodeInfo> nodes = roleNodes(dataset, roleId);
        List<ResourceExplorerTreeDataset.VmInfo> vms = roleVms(dataset, roleId);

        Set<String> deployments = new LinkedHashSet<>();
        nodes.forEach(n -> deployments.add(n.deploymentId()));
        vms.forEach(v -> deployments.add(v.deploymentId()));

        List<String> statuses = new ArrayList<>();
        nodes.forEach(n -> statuses.add(n.status()));
        vms.forEach(v -> statuses.add(v.status()));

        return new RoleSummaryResponse(
                role.memberCount(),
                deployments.size(),
                nodes.size(),
                vms.size(),
                countStatus(statuses, ResourceStatus.RUNNING),
                countStatus(statuses, ResourceStatus.PENDING),
                countStatus(statuses, ResourceStatus.STOP),
                countStatus(statuses, ResourceStatus.ERROR));
    }

    /** 노드 상태 위젯 — 상태별 분포 도넛 (전체 자원 패널과 동일 구조) */
    public ResourceStatusDistributionResponse getNodeStatus(String roleId) {
        ResourceExplorerTreeDataset dataset = loadDataset();
        accessibleRole(dataset, roleId);
        List<String> statuses = roleNodes(dataset, roleId).stream()
                .map(ResourceExplorerTreeDataset.NodeInfo::status)
                .toList();
        return distribution("NODE", statuses);
    }

    /** 가상머신 상태 위젯 — 상태별 분포 도넛 (전체 자원 패널과 동일 구조) */
    public ResourceStatusDistributionResponse getVmStatus(String roleId) {
        ResourceExplorerTreeDataset dataset = loadDataset();
        accessibleRole(dataset, roleId);
        List<String> statuses = roleVms(dataset, roleId).stream()
                .map(ResourceExplorerTreeDataset.VmInfo::status)
                .toList();
        return distribution("VM", statuses);
    }

    /** 배포영역 섹션 — 배포영역별 노드/가상머신 수와 상태 스택바, 키워드 검색·컬럼 정렬·페이징(1부터) */
    public PageResponse<RoleDeploymentListItemResponse> getRoleDeployments(String roleId, String keyword, int page, int size, String sort) {
        // TODO:: 배포영역 원장 연동 전까지는 노드·VM 에서 유도하므로 자원이 하나도 없는 배포영역은 표시되지 않는다
        ResourceExplorerTreeDataset dataset = loadDataset();
        accessibleRole(dataset, roleId);

        Set<String> deployments = new LinkedHashSet<>();
        roleNodes(dataset, roleId).forEach(n -> deployments.add(n.deploymentId()));
        roleVms(dataset, roleId).forEach(v -> deployments.add(v.deploymentId()));

        List<RoleDeploymentListItemResponse> rows = deployments.stream()
                .map(deploymentId -> deploymentRow(dataset, roleId, deploymentId))
                .filter(row -> matchesKeyword(row, keyword))
                .sorted(comparator(sort))
                .toList();
        return PageResponse.of(rows, page, size);
    }

    // ── 역할 접근 검증 ──────────────────────────────────────────

    /** roleId 가 데이터셋에 존재하고 현재 사용자 토큰에 부여된 역할일 때만 반환한다. 아니면 404. */
    private ResourceExplorerTreeDataset.RoleInfo accessibleRole(ResourceExplorerTreeDataset dataset, String roleId) {
        return dataset.roles().stream()
                .filter(r -> r.roleId().equals(roleId))
                .filter(r -> resourceExplorerTreeService.currentUserRoleIds().contains(r.roleId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "역할을 찾을 수 없습니다: " + roleId));
    }

    // ── 집계 내부 ───────────────────────────────────────────────
    private ResourceExplorerTreeDataset loadDataset() {
        // TODO:: 자원 관리 데이터(역할·노드·VM 원장) 연동 시 Mock 분기를 실제 조회로 교체
        if (properties.isMockEnabled()) {
            return mockDataProvider.getResourceExplorerTreeDataset();
        }
        throw new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "역할 스코프 자원 데이터와 자원 탐색기 역할 패널의 매핑 구현 전입니다.");
    }

    private static List<ResourceExplorerTreeDataset.NodeInfo> roleNodes(
            ResourceExplorerTreeDataset dataset, String roleId) {
        return dataset.nodes().stream().filter(n -> n.roleId().equals(roleId)).toList();
    }

    private static List<ResourceExplorerTreeDataset.VmInfo> roleVms(
            ResourceExplorerTreeDataset dataset, String roleId) {
        return dataset.vms().stream().filter(v -> v.roleId().equals(roleId)).toList();
    }

    private RoleDeploymentListItemResponse deploymentRow(ResourceExplorerTreeDataset dataset, String roleId, String deploymentId) {
        List<String> nodeStatuses = roleNodes(dataset, roleId).stream()
                .filter(n -> n.deploymentId().equals(deploymentId))
                .map(ResourceExplorerTreeDataset.NodeInfo::status)
                .toList();
        List<String> vmStatuses = roleVms(dataset, roleId).stream()
                .filter(v -> v.deploymentId().equals(deploymentId))
                .map(ResourceExplorerTreeDataset.VmInfo::status)
                .toList();

        // TODO:: 배포영역 원장 연동 시 표시명을 원장의 이름으로 교체 (현재는 deploymentId 를 이름으로 사용)
        return new RoleDeploymentListItemResponse(
                deploymentId, deploymentId,
                nodeStatuses.size(), statusSummary(nodeStatuses),
                vmStatuses.size(), statusSummary(vmStatuses));
    }

    /** 상태 스택바 — 자원이 없어도 "0/0/0/0"으로 표시하므로 null 없이 항상 값을 채운다 */
    private static StatusSummary statusSummary(List<String> statuses) {
        return new StatusSummary(
                statuses.size(),
                countStatus(statuses, ResourceStatus.RUNNING),
                countStatus(statuses, ResourceStatus.ERROR),
                countStatus(statuses, ResourceStatus.PENDING),
                countStatus(statuses, ResourceStatus.STOP));
    }

    /** 도넛 응답 — 범례 고정을 위해 카운트 0인 상태도 Running/Error/Pending/Stop 순으로 전부 내려준다 */
    private static ResourceStatusDistributionResponse distribution(String resourceKind, List<String> statuses) {
        List<StatusCount> counts = List.of(
                new StatusCount(ResourceStatus.RUNNING, countStatus(statuses, ResourceStatus.RUNNING)),
                new StatusCount(ResourceStatus.ERROR, countStatus(statuses, ResourceStatus.ERROR)),
                new StatusCount(ResourceStatus.PENDING, countStatus(statuses, ResourceStatus.PENDING)),
                new StatusCount(ResourceStatus.STOP, countStatus(statuses, ResourceStatus.STOP)));
        return new ResourceStatusDistributionResponse(resourceKind, statuses.size(), counts);
    }

    private static long countStatus(List<String> statuses, ResourceStatus status) {
        return statuses.stream().filter(s -> status.name().equalsIgnoreCase(s)).count();
    }

    private static boolean matchesKeyword(RoleDeploymentListItemResponse row, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        return row.name().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    /** sort 파라미터("{필드},{asc|desc}")를 Comparator 로 해석한다. 미지원 필드는 400. */
    private static Comparator<RoleDeploymentListItemResponse> comparator(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        Comparator<RoleDeploymentListItemResponse> comparator = switch (field) {
            case "name" -> Comparator.comparing(RoleDeploymentListItemResponse::name);
            case "nodeCount" -> Comparator.comparingLong(RoleDeploymentListItemResponse::nodeCount);
            case "vmCount" -> Comparator.comparingLong(RoleDeploymentListItemResponse::vmCount);
            default -> throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "지원하지 않는 정렬 필드입니다: " + field);
        };
        return desc ? comparator.reversed() : comparator;
    }
}

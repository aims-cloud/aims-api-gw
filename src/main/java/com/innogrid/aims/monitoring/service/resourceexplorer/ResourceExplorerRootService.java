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
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RoleListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RoleListItemResponse.StatusSummary;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RootSummaryResponse;
import com.innogrid.aims.monitoring.model.ResourceExplorerTreeDataset;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 전체 자원(트리 루트) 패널 조회 서비스.
 * KPI 카드 8종, 노드/가상머신 상태 도넛, 역할 테이블을 제공한다.
 *
 * "전체 자원"은 시스템 전체가 아니라 현재 사용자 토큰에 부여된 역할 스코프의 전체다 —
 * 트리(ResourceExplorerTreeService)와 같은 자원 계층 데이터셋을 같은 역할 스코프로
 * 집계하므로 트리에 보이는 자원 수와 패널의 카운트가 항상 일치한다.
 * 역할 패널은 ResourceExplorerRoleService 가 담당한다.
 */
@Service
public class ResourceExplorerRootService {

    // TODO:: 자원 관리 데이터 연동 시 Mock 플래그 의존성 제거
    private final ResourceExplorerProperties properties;
    // TODO:: 자원 관리 데이터 연동 시 MockDataProvider 의존성을 실제 원장 조회로 교체
    private final MockDataProvider mockDataProvider;
    // TODO:: AuthService 로 공통화
    private final ResourceExplorerTreeService resourceExplorerTreeService;

    public ResourceExplorerRootService(ResourceExplorerProperties properties, MockDataProvider mockDataProvider, ResourceExplorerTreeService resourceExplorerTreeService) {
        this.properties = properties;
        this.mockDataProvider = mockDataProvider;
        this.resourceExplorerTreeService = resourceExplorerTreeService;
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** KPI 카드 8종 — 역할/배포영역/노드/가상머신 수, Running/Error/Pending/Stop(노드+VM 합산) */
    public RootSummaryResponse getRootSummary() {
        ResourceExplorerTreeDataset dataset = userScopedDataset();

        Set<String> deployments = new LinkedHashSet<>();
        dataset.nodes().forEach(n -> deployments.add(n.deploymentId()));
        dataset.vms().forEach(v -> deployments.add(v.deploymentId()));

        List<String> statuses = new ArrayList<>();
        dataset.nodes().forEach(n -> statuses.add(n.status()));
        dataset.vms().forEach(v -> statuses.add(v.status()));

        return new RootSummaryResponse(
                dataset.roles().size(),
                deployments.size(),
                dataset.nodes().size(),
                dataset.vms().size(),
                countStatus(statuses, ResourceStatus.RUNNING),
                countStatus(statuses, ResourceStatus.ERROR),
                countStatus(statuses, ResourceStatus.PENDING),
                countStatus(statuses, ResourceStatus.STOP));
    }

    /** 노드 상태 도넛 — 총계(하이퍼바이저 수)와 상태별 카운트 */
    public ResourceStatusDistributionResponse getNodeStatus() {
        List<String> statuses = userScopedDataset().nodes().stream()
                .map(ResourceExplorerTreeDataset.NodeInfo::status)
                .toList();
        return distribution("NODE", statuses);
    }

    /** 가상머신 상태 도넛 — 총계와 상태별 카운트 */
    public ResourceStatusDistributionResponse getVmStatus() {
        List<String> statuses = userScopedDataset().vms().stream()
                .map(ResourceExplorerTreeDataset.VmInfo::status)
                .toList();
        return distribution("VM", statuses);
    }

    /** 역할 테이블 — 관리자/멤버·배포영역·노드·VM 수/상태 스택바, 키워드 검색·컬럼 정렬·페이징(1부터) */
    public PageResponse<RoleListItemResponse> getRoles(String keyword, int page, int size, String sort) {
        ResourceExplorerTreeDataset dataset = userScopedDataset();

        List<RoleListItemResponse> rows = dataset.roles().stream()
                .map(role -> roleRow(dataset, role))
                .filter(row -> matchesKeyword(row, keyword))
                .sorted(comparator(sort))
                .toList();
        return PageResponse.of(rows, page, size);
    }

    // ── 집계 내부 ───────────────────────────────────────────────

    /** 데이터셋을 현재 사용자 토큰의 역할 스코프로 축소한다 — 트리와 같은 범위를 집계하게 된다. */
    private ResourceExplorerTreeDataset userScopedDataset() {
        ResourceExplorerTreeDataset dataset = loadDataset();
        Set<String> userRoles = resourceExplorerTreeService.currentUserRoleIds();
        return new ResourceExplorerTreeDataset(
                dataset.roles().stream().filter(r -> userRoles.contains(r.roleId())).toList(),
                dataset.nodes().stream().filter(n -> userRoles.contains(n.roleId())).toList(),
                dataset.vms().stream().filter(v -> userRoles.contains(v.roleId())).toList());
    }


    private ResourceExplorerTreeDataset loadDataset() {
        // TODO:: 자원 관리 데이터(역할·노드·VM 원장) 연동 시 Mock 분기를 실제 조회로 교체
        if (properties.isMockEnabled()) {
            return mockDataProvider.getResourceExplorerTreeDataset();
        }
        throw new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "전체 자원 집계 데이터와 자원 탐색기 전체 자원 패널의 매핑 구현 전입니다.");
    }

    private RoleListItemResponse roleRow(ResourceExplorerTreeDataset dataset, ResourceExplorerTreeDataset.RoleInfo role) {
        List<String> nodeStatuses = dataset.nodes().stream()
                .filter(n -> n.roleId().equals(role.roleId()))
                .map(ResourceExplorerTreeDataset.NodeInfo::status)
                .toList();
        List<String> vmStatuses = dataset.vms().stream()
                .filter(v -> v.roleId().equals(role.roleId()))
                .map(ResourceExplorerTreeDataset.VmInfo::status)
                .toList();

        Set<String> deployments = new LinkedHashSet<>();
        dataset.nodes().stream()
                .filter(n -> n.roleId().equals(role.roleId()))
                .forEach(n -> deployments.add(n.deploymentId()));
        dataset.vms().stream()
                .filter(v -> v.roleId().equals(role.roleId()))
                .forEach(v -> deployments.add(v.deploymentId()));

        return new RoleListItemResponse(
                role.roleId(),
                role.roleName(),
                role.managerName(),
                role.memberCount(),
                deployments.size(),
                nodeStatuses.size(),
                statusSummary(nodeStatuses),
                vmStatuses.size(),
                statusSummary(vmStatuses));
    }

    /** 상태 스택바 — 해당 자원이 하나도 없으면 null("-" 표기), 있으면 상태별 카운트 */
    private static StatusSummary statusSummary(List<String> statuses) {
        if (statuses.isEmpty()) {
            return null;
        }
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

    private static boolean matchesKeyword(RoleListItemResponse row, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        return row.name().toLowerCase(Locale.ROOT).contains(lower)
                || (row.managerName() != null && row.managerName().toLowerCase(Locale.ROOT).contains(lower));
    }

    /** sort 파라미터("{필드},{asc|desc}")를 Comparator 로 해석한다. 미지원 필드는 400. */
    private static Comparator<RoleListItemResponse> comparator(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        Comparator<RoleListItemResponse> comparator = switch (field) {
            case "name" -> Comparator.comparing(RoleListItemResponse::name);
            case "managerName" -> Comparator.comparing(RoleListItemResponse::managerName,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "memberCount" -> Comparator.comparingLong(RoleListItemResponse::memberCount);
            case "deploymentCount" -> Comparator.comparingLong(RoleListItemResponse::deploymentCount);
            case "nodeCount" -> Comparator.comparingLong(RoleListItemResponse::nodeCount);
            case "vmCount" -> Comparator.comparingLong(RoleListItemResponse::vmCount);
            default -> throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "지원하지 않는 정렬 필드입니다: " + field);
        };
        return desc ? comparator.reversed() : comparator;
    }
}

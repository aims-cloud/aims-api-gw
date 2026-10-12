package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.TreeSet;

import com.innogrid.aims.auth.repository.UserRepository;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.util.ListSort;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse.StatusCount;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RoleListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RoleListItemResponse.StatusSummary;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RootSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedVm;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 전체 자원(트리 루트) 패널 조회 서비스.
 * KPI 카드 8종, 노드/가상머신 상태 도넛, 역할 테이블을 제공한다.
 *
 * "전체 자원"은 시스템 전체가 아니라 현재 사용자 토큰에 부여된 역할 스코프의 전체다 —
 * 트리와 같은 원장(role_assets·assets·monitoring_target·role_namespaces·Tumblebug)과 같은 Thanos 스냅샷을
 * {@link ResourceScopeService} 로 조회하므로 트리에 보이는 자원 수와 패널의 카운트가 항상 일치한다.
 * 역할 패널은 ResourceExplorerRoleService 가 담당한다.
 */
@Service
public class ResourceExplorerRootService {

    /** sort 파라미터("{필드},{asc|desc}")를 Comparator 로 해석한다. 미지원 필드는 400. */
    private static final ListSort.Spec<RoleListItemResponse> SORT = ListSort.spec(
            Map.ofEntries(
                    Map.entry("name", ListSort.by(RoleListItemResponse::name)),
                    Map.entry("managerName", ListSort.byNullsAsLargest(RoleListItemResponse::managerName)),
                    Map.entry("memberCount", ListSort.by(RoleListItemResponse::memberCount)),
                    Map.entry("mdcCount", ListSort.by(RoleListItemResponse::mdcCount)),
                    Map.entry("nodeCount", ListSort.by(RoleListItemResponse::nodeCount)),
                    Map.entry("vmCount", ListSort.by(RoleListItemResponse::vmCount))),
            null);

    private final ResourceScopeService scopeService;
    private final UserRepository userRepository;

    public ResourceExplorerRootService(ResourceScopeService scopeService, UserRepository userRepository) {
        this.scopeService = scopeService;
        this.userRepository = userRepository;
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** KPI 카드 8종 — 역할/MDC/물리 호스트/가상머신 수, Running/Error/Pending/Stop(호스트+VM 합산, 자원 ID 기준 중복 제거) */
    public RootSummaryResponse getRootSummary() {
        Scope scope = scopeService.currentUserScope();

        List<ResourceStatus> statuses = new ArrayList<>();
        scope.distinctHosts().forEach(h -> statuses.add(h.status()));
        scope.distinctVms().forEach(v -> statuses.add(v.status()));

        return new RootSummaryResponse(
                scope.roles().size(),
                scope.mdcIds().size(),
                scope.distinctHosts().size(),
                scope.distinctVms().size(),
                count(statuses, ResourceStatus.RUNNING),
                count(statuses, ResourceStatus.ERROR),
                count(statuses, ResourceStatus.PENDING),
                count(statuses, ResourceStatus.STOP));
    }

    /** 노드 상태 도넛 — 총계(물리 호스트 수)와 상태별 카운트 */
    public ResourceStatusDistributionResponse getNodeStatus() {
        List<ResourceStatus> statuses = scopeService.currentUserScope().distinctHosts().stream()
                .map(ScopedHost::status)
                .toList();
        return distribution("NODE", statuses);
    }

    /** 가상머신 상태 도넛 — 총계와 상태별 카운트 (1세부 가상자원 + Tumblebug VM) */
    public ResourceStatusDistributionResponse getVmStatus() {
        List<ResourceStatus> statuses = scopeService.currentUserScope().distinctVms().stream()
                .map(ScopedVm::status)
                .toList();
        return distribution("VM", statuses);
    }

    /** 역할 테이블 — 관리자/멤버·MDC·노드·VM 수/상태 스택바, 키워드 검색·컬럼 정렬·페이징(1부터) */
    public PageResponse<RoleListItemResponse> getRoles(String keyword, int page, int size, String sort) {
        Comparator<RoleListItemResponse> comparator = SORT.comparator(sort);   // 미지원 필드는 조회 전에 400
        Scope scope = scopeService.currentUserScope();

        List<RoleListItemResponse> rows = scope.roles().stream()
                .map(role -> roleRow(scope, role))
                .filter(row -> matchesKeyword(row, keyword))
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    // ── 집계 내부 ───────────────────────────────────────────────

    private RoleListItemResponse roleRow(Scope scope, String role) {
        List<ScopedHost> hosts = scope.hostsOf(role);
        List<ScopedVm> vms = scope.vmsOf(role);
        List<ResourceStatus> hostStatuses = hosts.stream().map(ScopedHost::status).toList();
        List<ResourceStatus> vmStatuses = vms.stream().map(ScopedVm::status).toList();
        long mdcCount = hosts.stream().map(ScopedHost::mdcId).collect(java.util.stream.Collectors.toCollection(TreeSet::new)).size();

        return new RoleListItemResponse(
                role,
                role,
                null,                                                   // TODO:: 역할 관리자 개념 확정 시 연결
                userRepository.countByRoles_Name(role),
                mdcCount,
                hosts.size(),
                statusSummary(hostStatuses),
                vms.size(),
                statusSummary(vmStatuses));
    }

    /** 상태 스택바 — 해당 자원이 하나도 없으면 null("-" 표기), 있으면 상태별 카운트 */
    private static StatusSummary statusSummary(List<ResourceStatus> statuses) {
        if (statuses.isEmpty()) {
            return null;
        }
        return new StatusSummary(
                statuses.size(),
                count(statuses, ResourceStatus.RUNNING),
                count(statuses, ResourceStatus.ERROR),
                count(statuses, ResourceStatus.PENDING),
                count(statuses, ResourceStatus.STOP));
    }

    /** 도넛 응답 — 범례 고정을 위해 카운트 0인 상태도 Running/Error/Pending/Stop 순으로 전부 내려준다 */
    private static ResourceStatusDistributionResponse distribution(String resourceKind, List<ResourceStatus> statuses) {
        List<StatusCount> counts = List.of(
                new StatusCount(ResourceStatus.RUNNING, count(statuses, ResourceStatus.RUNNING)),
                new StatusCount(ResourceStatus.ERROR, count(statuses, ResourceStatus.ERROR)),
                new StatusCount(ResourceStatus.PENDING, count(statuses, ResourceStatus.PENDING)),
                new StatusCount(ResourceStatus.STOP, count(statuses, ResourceStatus.STOP)));
        return new ResourceStatusDistributionResponse(resourceKind, statuses.size(), counts);
    }

    private static long count(List<ResourceStatus> statuses, ResourceStatus status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    private static boolean matchesKeyword(RoleListItemResponse row, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        return row.name().toLowerCase(Locale.ROOT).contains(lower)
                || (row.managerName() != null && row.managerName().toLowerCase(Locale.ROOT).contains(lower));
    }

}

package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.innogrid.aims.auth.repository.UserRepository;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse.StatusCount;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.StatusSummary;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedNamespace;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedVm;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 역할 패널 조회 서비스.
 * 트리에서 역할 선택 시 KPI 카드, 노드/가상머신 상태 위젯, 자원 영역(MDC·Namespace) 목록을 제공한다.
 *
 * 경로의 roleName 이 현재 사용자 토큰에 부여된 역할이 아니면 404 를 반환한다 —
 * 트리에 노출되지 않는 역할의 패널을 URL 로 직접 조회할 수 없다(트리·전체 자원 패널과 동일 스코프).
 * 자원 목록은 트리와 같은 원장을 쓰는 {@link ResourceScopeService} 에서 해당 역할만 조회한다.
 */
@Service
public class ResourceExplorerRoleService {

    private final ResourceScopeService scopeService;
    private final UserRepository userRepository;
    private final AuthService authService;

    public ResourceExplorerRoleService(ResourceScopeService scopeService,
                                       UserRepository userRepository,
                                       AuthService authService) {
        this.scopeService = scopeService;
        this.userRepository = userRepository;
        this.authService = authService;
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** KPI 카드 — 멤버/MDC/Namespace/물리 호스트/가상머신 수, Running/Pending/Stop/Error(호스트+VM 합산) */
    public RoleSummaryResponse getRoleSummary(String roleName) {
        Scope scope = accessibleScope(roleName);
        List<ScopedHost> hosts = scope.hostsOf(roleName);
        List<ScopedVm> vms = scope.vmsOf(roleName);

        List<ResourceStatus> statuses = new ArrayList<>();
        hosts.forEach(h -> statuses.add(h.status()));
        vms.forEach(v -> statuses.add(v.status()));

        return new RoleSummaryResponse(
                userRepository.countByRoles_Name(roleName),
                hosts.stream().map(ScopedHost::mdcId).distinct().count(),
                scope.namespacesOf(roleName).size(),
                hosts.size(),
                vms.size(),
                count(statuses, ResourceStatus.RUNNING),
                count(statuses, ResourceStatus.PENDING),
                count(statuses, ResourceStatus.STOP),
                count(statuses, ResourceStatus.ERROR));
    }

    /** 노드 상태 위젯 — 상태별 분포 도넛 (전체 자원 패널과 동일 구조) */
    public ResourceStatusDistributionResponse getNodeStatus(String roleName) {
        List<ResourceStatus> statuses = accessibleScope(roleName).hostsOf(roleName).stream()
                .map(ScopedHost::status)
                .toList();
        return distribution("NODE", statuses);
    }

    /** 가상머신 상태 위젯 — 상태별 분포 도넛 (1세부 가상자원 + Tumblebug VM) */
    public ResourceStatusDistributionResponse getVmStatus(String roleName) {
        List<ResourceStatus> statuses = accessibleScope(roleName).vmsOf(roleName).stream()
                .map(ScopedVm::status)
                .toList();
        return distribution("VM", statuses);
    }

    /** 자원 영역 섹션 — MDC 행(물리 호스트+1세부 VM)과 Namespace 행(Tumblebug VM), 키워드 검색·컬럼 정렬·페이징(1부터) */
    public PageResponse<RoleAreaListItemResponse> getRoleAreas(String roleName, String keyword, int page, int size, String sort) {
        Comparator<RoleAreaListItemResponse> comparator = comparator(sort);   // 미지원 필드는 조회 전에 400
        Scope scope = accessibleScope(roleName);

        List<RoleAreaListItemResponse> rows = new ArrayList<>();
        rows.addAll(mdcRows(scope.hostsOf(roleName), scope.vmsOf(roleName)));
        rows.addAll(namespaceRows(scope.namespacesOf(roleName), scope.vmsOf(roleName)));

        List<RoleAreaListItemResponse> filtered = rows.stream()
                .filter(row -> matchesKeyword(row, keyword))
                .sorted(comparator)
                .toList();
        return PageResponse.of(filtered, page, size);
    }

    // ── 역할 접근 검증 ──────────────────────────────────────────

    /** roleName 이 현재 사용자 토큰에 부여된 역할일 때만 그 역할의 스코프를 조회한다. 아니면 404. */
    private Scope accessibleScope(String roleName) {
        if (!authService.currentUserRoleIds().contains(roleName)) {
            throw BusinessException.notFound("역할을 찾을 수 없습니다: " + roleName);
        }
        return scopeService.scopeOf(List.of(roleName));
    }

    // ── 집계 내부 ───────────────────────────────────────────────

    /** MDC 행 — 물리 호스트를 mdcId 로 묶고, 그 호스트에 배치된 1세부 VM 을 가상머신으로 집계 */
    private static List<RoleAreaListItemResponse> mdcRows(List<ScopedHost> hosts, List<ScopedVm> vms) {
        Map<String, List<ResourceStatus>> hostStatuses = new TreeMap<>();
        hosts.forEach(h -> hostStatuses.computeIfAbsent(h.mdcId(), k -> new ArrayList<>()).add(h.status()));

        List<RoleAreaListItemResponse> rows = new ArrayList<>();
        hostStatuses.forEach((mdcId, statuses) -> {
            List<ResourceStatus> vmStatuses = vms.stream()
                    .filter(v -> mdcId.equals(v.mdcId()))
                    .map(ScopedVm::status)
                    .toList();
            rows.add(new RoleAreaListItemResponse(mdcId, AreaKind.MDC, mdcId,
                    statuses.size(), statusSummary(statuses),
                    vmStatuses.size(), statusSummary(vmStatuses)));
        });
        return rows;
    }

    /** Namespace 행 — 역할에 매핑된 Namespace 마다 Tumblebug VM 을 집계 (VM 이 없어도 행은 나온다) */
    private static List<RoleAreaListItemResponse> namespaceRows(List<ScopedNamespace> namespaces, List<ScopedVm> vms) {
        List<RoleAreaListItemResponse> rows = new ArrayList<>();
        for (ScopedNamespace ns : namespaces) {
            List<ResourceStatus> vmStatuses = vms.stream()
                    .filter(v -> ns.nsId().equals(v.nsId()))
                    .map(ScopedVm::status)
                    .toList();
            rows.add(new RoleAreaListItemResponse("TB:" + ns.nsId(), AreaKind.NAMESPACE, ns.name(),
                    0, statusSummary(List.of()),
                    vmStatuses.size(), statusSummary(vmStatuses)));
        }
        return rows;
    }

    /** 상태 스택바 — 자원이 없어도 "0/0/0/0"으로 표시하므로 null 없이 항상 값을 채운다 */
    private static StatusSummary statusSummary(List<ResourceStatus> statuses) {
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

    private static boolean matchesKeyword(RoleAreaListItemResponse row, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        return row.name().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    /** sort 파라미터("{필드},{asc|desc}")를 Comparator 로 해석한다. 미지원 필드는 400. */
    private static Comparator<RoleAreaListItemResponse> comparator(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        Comparator<RoleAreaListItemResponse> comparator = switch (field) {
            case "name" -> Comparator.comparing(RoleAreaListItemResponse::name, String.CASE_INSENSITIVE_ORDER);
            case "kind" -> Comparator.comparing(RoleAreaListItemResponse::kind);
            case "nodeCount" -> Comparator.comparingLong(RoleAreaListItemResponse::nodeCount);
            case "vmCount" -> Comparator.comparingLong(RoleAreaListItemResponse::vmCount);
            default -> throw BusinessException.badRequest("지원하지 않는 정렬 필드입니다: " + field);
        };
        return desc ? comparator.reversed() : comparator;
    }
}

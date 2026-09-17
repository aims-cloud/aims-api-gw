package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse.StatusCount;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusSummary;
import com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot.PhysicalRootMdcListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot.PhysicalRootSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.Scope;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedVm;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 "물리 노드" 그룹 루트(PHYSICAL_ROOT) 패널 조회 서비스.
 * 트리에서 역할 하위 물리 노드 그룹을 선택했을 때 KPI 카드, 노드 상태 도넛, MDC 목록을 제공한다.
 *
 * 역할 패널의 물리 절반이다 — 같은 스코프 원장({@link ResourceScopeService})을 쓰므로 노드 상태 도넛은 역할 패널의
 * node-status 와 값이 같다(트리 노드 하나에 패널 API 한 묶음이 대응하도록 따로 둔다).
 * 경로의 roleName 이 현재 사용자 토큰에 부여된 역할이 아니면 404.
 */
@Service
public class ResourceExplorerPhysicalRootService {

    private final ResourceScopeService scopeService;
    private final AuthService authService;

    public ResourceExplorerPhysicalRootService(ResourceScopeService scopeService, AuthService authService) {
        this.scopeService = scopeService;
        this.authService = authService;
    }

    /** KPI 카드 — MDC 수, 물리 호스트 수, 호스트 위 1세부 가상자원 수, 호스트 Running/Pending/Stop/Error */
    public PhysicalRootSummaryResponse getSummary(String roleName) {
        Scope scope = accessibleScope(roleName);
        List<ScopedHost> hosts = scope.hostsOf(roleName);
        List<ResourceStatus> statuses = hosts.stream().map(ScopedHost::status).toList();
        return new PhysicalRootSummaryResponse(
                hosts.stream().map(ScopedHost::mdcId).distinct().count(),
                hosts.size(),
                hostVms(scope, roleName).size(),
                count(statuses, ResourceStatus.RUNNING),
                count(statuses, ResourceStatus.PENDING),
                count(statuses, ResourceStatus.STOP),
                count(statuses, ResourceStatus.ERROR));
    }

    /** 노드 상태 도넛 — 카운트 0인 상태도 Running/Error/Pending/Stop 순으로 전부 */
    public ResourceStatusDistributionResponse getNodeStatus(String roleName) {
        List<ResourceStatus> statuses = accessibleScope(roleName).hostsOf(roleName).stream()
                .map(ScopedHost::status)
                .toList();
        return new ResourceStatusDistributionResponse("NODE", statuses.size(), List.of(
                new StatusCount(ResourceStatus.RUNNING, count(statuses, ResourceStatus.RUNNING)),
                new StatusCount(ResourceStatus.ERROR, count(statuses, ResourceStatus.ERROR)),
                new StatusCount(ResourceStatus.PENDING, count(statuses, ResourceStatus.PENDING)),
                new StatusCount(ResourceStatus.STOP, count(statuses, ResourceStatus.STOP))));
    }

    /** MDC 목록 — MDC 별 호스트 수·상태, 1세부 가상자원 수·상태. 이름 키워드·컬럼 정렬(name|nodeCount|vmCount)·페이징(1부터) */
    public PageResponse<PhysicalRootMdcListItemResponse> getMdcs(String roleName, String keyword, int page, int size, String sort) {
        Comparator<PhysicalRootMdcListItemResponse> comparator = comparator(sort);   // 미지원 필드는 조회 전에 400
        Scope scope = accessibleScope(roleName);

        Map<String, List<ResourceStatus>> hostStatuses = new TreeMap<>();
        scope.hostsOf(roleName).forEach(h -> hostStatuses.computeIfAbsent(h.mdcId(), k -> new ArrayList<>()).add(h.status()));
        List<ScopedVm> vms = hostVms(scope, roleName);

        List<PhysicalRootMdcListItemResponse> rows = new ArrayList<>();
        hostStatuses.forEach((mdcId, statuses) -> {
            List<ResourceStatus> vmStatuses = vms.stream().filter(v -> mdcId.equals(v.mdcId())).map(ScopedVm::status).toList();
            rows.add(new PhysicalRootMdcListItemResponse(mdcId, mdcId,
                    statuses.size(), ResourceStatusSummary.of(statuses),
                    vmStatuses.size(), ResourceStatusSummary.of(vmStatuses)));
        });
        List<PhysicalRootMdcListItemResponse> filtered = rows.stream()
                .filter(row -> matchesKeyword(row, keyword))
                .sorted(comparator)
                .toList();
        return PageResponse.of(filtered, page, size);
    }

    // ── 내부 ────────────────────────────────────────────────────

    /** roleName 이 현재 사용자 토큰에 부여된 역할일 때만 그 역할의 스코프를 조회한다. 아니면 404. */
    private Scope accessibleScope(String roleName) {
        if (!authService.currentUserRoleIds().contains(roleName)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "역할을 찾을 수 없습니다: " + roleName);
        }
        return scopeService.scopeOf(List.of(roleName));
    }

    /** 물리 호스트 위에 배치된 1세부 가상자원만 — Tumblebug VM(mdcId 없음)은 가상머신 그룹 루트가 센다 */
    private static List<ScopedVm> hostVms(Scope scope, String roleName) {
        return scope.vmsOf(roleName).stream().filter(v -> v.mdcId() != null).toList();
    }

    private static long count(List<ResourceStatus> statuses, ResourceStatus status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    private static boolean matchesKeyword(PhysicalRootMdcListItemResponse row, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        return row.name().toLowerCase(Locale.ROOT).contains(keyword.trim().toLowerCase(Locale.ROOT));
    }

    /** sort 파라미터("{필드},{asc|desc}") — name|nodeCount|vmCount, 동률은 이름순. 미지원 필드는 400 */
    private static Comparator<PhysicalRootMdcListItemResponse> comparator(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        Comparator<PhysicalRootMdcListItemResponse> comparator = switch (field) {
            case "name" -> Comparator.comparing(PhysicalRootMdcListItemResponse::name, String.CASE_INSENSITIVE_ORDER);
            case "nodeCount" -> Comparator.comparingLong(PhysicalRootMdcListItemResponse::nodeCount);
            case "vmCount" -> Comparator.comparingLong(PhysicalRootMdcListItemResponse::vmCount);
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "지원하지 않는 정렬 필드입니다: " + field);
        };
        Comparator<PhysicalRootMdcListItemResponse> stable = comparator.thenComparing(PhysicalRootMdcListItemResponse::name, String.CASE_INSENSITIVE_ORDER);
        return desc ? stable.reversed() : stable;
    }
}

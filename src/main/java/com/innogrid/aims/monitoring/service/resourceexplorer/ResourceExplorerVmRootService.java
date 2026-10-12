package com.innogrid.aims.monitoring.service.resourceexplorer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.util.ListSort;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse.StatusCount;
import com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot.VmRootNamespaceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot.VmRootSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.TumblebugPanelSupport;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.TumblebugPanelSupport.RoleNamespaces;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 "가상머신" 그룹 루트(VM_ROOT) 패널 조회 서비스.
 * 트리에서 역할 하위 가상머신 그룹을 선택했을 때 KPI 카드, 가상머신 상태 도넛, Namespace 목록을 제공한다.
 *
 * 데이터는 트리·Namespace 패널과 같은 Tumblebug 스냅샷이다 — 역할에 매핑된 Namespace 가운데 Tumblebug 에 존재하는 것만
 * 트리처럼 센다. 상태는 Node(VM) 상태의 §6.1 버킷 집계. 조회 실패 Namespace 는 이전 값을 유지하며
 * {@code tumblebugUnavailable} 로 표시한다. 경로의 roleName 이 현재 사용자 토큰의 역할이 아니면 404.
 */
@Service
public class ResourceExplorerVmRootService {

    /** sort 파라미터("{필드},{asc|desc}") — name|infraCount|vmCount, 동률은 이름순. 미지원 필드는 400 */
    private static final ListSort.Spec<VmRootNamespaceListItemResponse> SORT = ListSort.spec(
            Map.ofEntries(
                    Map.entry("name", ListSort.by(VmRootNamespaceListItemResponse::name, String.CASE_INSENSITIVE_ORDER)),
                    Map.entry("infraCount", ListSort.by(VmRootNamespaceListItemResponse::infraCount)),
                    Map.entry("vmCount", ListSort.by(VmRootNamespaceListItemResponse::vmCount))),
            Comparator.comparing(VmRootNamespaceListItemResponse::name, String.CASE_INSENSITIVE_ORDER));

    private final TumblebugPanelSupport support;

    public ResourceExplorerVmRootService(TumblebugPanelSupport support) {
        this.support = support;
    }

    /** KPI 카드 — Namespace 수, Infra 수, VM 수, VM Running/Pending/Stop/Error, Tumblebug 조회 실패 여부 */
    public VmRootSummaryResponse getSummary(String roleName) {
        RoleNamespaces role = support.accessibleRoleNamespaces(roleName);
        List<String> nsIds = presentNamespaces(role);
        TbSnapshot tb = role.snapshot();

        List<TbInfra> infras = nsIds.stream()
                .flatMap(ns -> tb.infrasByNamespace().getOrDefault(ns, List.<TbInfra>of()).stream())
                .toList();
        List<ResourceStatus> statuses = TumblebugPanelSupport.statusesOf(nodesOf(infras));
        boolean unavailable = tb.degraded() || nsIds.stream().anyMatch(tb.failedNamespaces()::contains);
        return new VmRootSummaryResponse(
                nsIds.size(),
                infras.size(),
                statuses.size(),
                TumblebugPanelSupport.count(statuses, ResourceStatus.RUNNING),
                TumblebugPanelSupport.count(statuses, ResourceStatus.PENDING),
                TumblebugPanelSupport.count(statuses, ResourceStatus.STOP),
                TumblebugPanelSupport.count(statuses, ResourceStatus.ERROR),
                unavailable);
    }

    /** 가상머신 상태 도넛 — 카운트 0인 상태도 Running/Error/Pending/Stop 순으로 전부 */
    public ResourceStatusDistributionResponse getVmStatus(String roleName) {
        RoleNamespaces role = support.accessibleRoleNamespaces(roleName);
        TbSnapshot tb = role.snapshot();
        List<TbInfra> infras = presentNamespaces(role).stream()
                .flatMap(ns -> tb.infrasByNamespace().getOrDefault(ns, List.<TbInfra>of()).stream())
                .toList();
        List<ResourceStatus> statuses = TumblebugPanelSupport.statusesOf(nodesOf(infras));
        return new ResourceStatusDistributionResponse("VM", statuses.size(), List.of(
                new StatusCount(ResourceStatus.RUNNING, TumblebugPanelSupport.count(statuses, ResourceStatus.RUNNING)),
                new StatusCount(ResourceStatus.ERROR, TumblebugPanelSupport.count(statuses, ResourceStatus.ERROR)),
                new StatusCount(ResourceStatus.PENDING, TumblebugPanelSupport.count(statuses, ResourceStatus.PENDING)),
                new StatusCount(ResourceStatus.STOP, TumblebugPanelSupport.count(statuses, ResourceStatus.STOP))));
    }

    /** Namespace 목록 — Infra 수·VM 수·VM 상태·조회 실패 여부. 이름/nsId 키워드·컬럼 정렬(name|infraCount|vmCount)·페이징(1부터) */
    public PageResponse<VmRootNamespaceListItemResponse> getNamespaces(String roleName, String keyword, int page, int size, String sort) {
        Comparator<VmRootNamespaceListItemResponse> comparator = SORT.comparator(sort);   // 미지원 필드는 조회 전에 400
        RoleNamespaces role = support.accessibleRoleNamespaces(roleName);
        TbSnapshot tb = role.snapshot();

        List<VmRootNamespaceListItemResponse> rows = new ArrayList<>();
        for (String nsId : presentNamespaces(role)) {
            List<TbInfra> infras = tb.infrasByNamespace().getOrDefault(nsId, List.of());
            List<TbNode> nodes = nodesOf(infras);
            rows.add(new VmRootNamespaceListItemResponse(nsId, tb.namespaceNames().getOrDefault(nsId, nsId),
                    infras.size(), nodes.size(), TumblebugPanelSupport.summaryOf(nodes), tb.isUnavailable(nsId)));
        }
        List<VmRootNamespaceListItemResponse> filtered = rows.stream()
                .filter(row -> matchesKeyword(row, keyword))
                .sorted(comparator)
                .toList();
        return PageResponse.of(filtered, page, size);
    }

    // ── 내부 ────────────────────────────────────────────────────

    /** 역할에 매핑된 Namespace 가운데 Tumblebug 에 존재하는 것 — 트리와 같은 기준. GET /ns 실패(degraded)면 빈 목록 */
    private static List<String> presentNamespaces(RoleNamespaces role) {
        return role.nsIds().stream().filter(role.snapshot().namespaceNames()::containsKey).toList();
    }

    private static List<TbNode> nodesOf(List<TbInfra> infras) {
        return infras.stream().map(TbInfra::nodes).flatMap(List::stream).toList();
    }

    private static boolean matchesKeyword(VmRootNamespaceListItemResponse row, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String lower = keyword.trim().toLowerCase(Locale.ROOT);
        return row.name().toLowerCase(Locale.ROOT).contains(lower) || row.nsId().toLowerCase(Locale.ROOT).contains(lower);
    }

}

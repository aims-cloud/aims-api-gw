package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.namespace.InfraListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.namespace.NamespaceSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 Namespace 패널 조회 서비스 — 트리의 Tumblebug Namespace 선택 시 개요 탭의 KPI 카드와 Infra 목록.
 *
 * <ul>
 *   <li>스코프: nsId 가 토큰 역할(roleName 지정 시 그 역할)의 role_namespaces 매핑에 있어야 한다. 없으면 404.</li>
 *   <li>데이터: 트리와 같은 Tumblebug 스냅샷. 상태 카드·스택바는 Namespace 안 Node(VM) 상태의 §6.1 버킷 집계다.</li>
 *   <li>Infra 행에는 Tumblebug Infra 집계 상태 원문("Running:1 (R:1/1)")을 함께 싣는다.</li>
 * </ul>
 */
@Service
public class ResourceExplorerNamespaceService {

    private final TumblebugPanelSupport support;

    public ResourceExplorerNamespaceService(TumblebugPanelSupport support) {
        this.support = support;
    }

    /** Namespace KPI 카드 — Infra 수 / VM 수 / Running / Pending / Stop / Error */
    public NamespaceSummaryResponse getNamespaceSummary(String roleName, String nsId) {
        TbSnapshot tb = support.accessibleNamespace(roleName, nsId);
        List<TbInfra> infras = tb.infrasByNamespace().getOrDefault(nsId, List.of());
        List<ResourceStatus> statuses = TumblebugPanelSupport.statusesOf(
                infras.stream().map(TbInfra::nodes).flatMap(List::stream).toList());
        return new NamespaceSummaryResponse(
                nsId,
                tb.namespaceNames().getOrDefault(nsId, nsId),
                infras.size(),
                statuses.size(),
                TumblebugPanelSupport.count(statuses, ResourceStatus.RUNNING),
                TumblebugPanelSupport.count(statuses, ResourceStatus.PENDING),
                TumblebugPanelSupport.count(statuses, ResourceStatus.STOP),
                TumblebugPanelSupport.count(statuses, ResourceStatus.ERROR),
                tb.failedNamespaces().contains(nsId));
    }

    /** Infra 목록 — 이름 키워드 검색, 컬럼 정렬(name|tumblebugStatus|vmCount), 페이징(1부터) */
    public PageResponse<InfraListItemResponse> getNamespaceInfras(String roleName, String nsId, String keyword,
                                                                  int page, int size, String sort) {
        Comparator<InfraListItemResponse> comparator = comparator(sort);      // 미지원 필드는 조회 전에 400
        TbSnapshot tb = support.accessibleNamespace(roleName, nsId);
        List<InfraListItemResponse> rows = tb.infrasByNamespace().getOrDefault(nsId, List.of()).stream()
                .map(ResourceExplorerNamespaceService::row)
                .filter(row -> matchesKeyword(row, keyword))
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    private static InfraListItemResponse row(TbInfra infra) {
        List<TbNode> nodes = infra.nodes();
        return new InfraListItemResponse(infra.id(), infra.name(), infra.status(),
                nodes.size(), TumblebugPanelSupport.summaryOf(nodes));
    }

    private static boolean matchesKeyword(InfraListItemResponse row, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        return row.name().toLowerCase(Locale.ROOT).contains(lower)
                || row.infraId().toLowerCase(Locale.ROOT).contains(lower);
    }

    /** sort 파라미터("{필드},{asc|desc}")를 Comparator 로 해석한다. 동률은 이름순으로 고정. */
    private static Comparator<InfraListItemResponse> comparator(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        Comparator<InfraListItemResponse> comparator = switch (field) {
            case "name" -> Comparator.comparing(InfraListItemResponse::name, String.CASE_INSENSITIVE_ORDER);
            case "tumblebugStatus" -> Comparator.comparing(InfraListItemResponse::tumblebugStatus,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "vmCount" -> Comparator.comparingLong(InfraListItemResponse::vmCount);
            default -> throw BusinessException.badRequest("지원하지 않는 정렬 필드입니다: " + field);
        };
        Comparator<InfraListItemResponse> stable = comparator.thenComparing(InfraListItemResponse::name, String.CASE_INSENSITIVE_ORDER);
        return desc ? stable.reversed() : stable;
    }
}

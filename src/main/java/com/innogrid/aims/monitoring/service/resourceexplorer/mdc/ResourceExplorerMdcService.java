package com.innogrid.aims.monitoring.service.resourceexplorer.mdc;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.MdcSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeSearchField;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeTopResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService.HostUtilization;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 MDC 패널 조회 서비스.
 * 트리에서 역할 하위 MDC 를 선택했을 때 개요 탭의 KPI 카드·노드 Top-N·노드 목록을 제공한다.
 *
 * 조회 대상은 현재 사용자 토큰에 부여된 역할 스코프의 물리 호스트(트리와 같은 원장, {@link ResourceScopeService})다.
 * roleName/mdcId 파라미터는 스코프 안에서의 필터 — 토큰에 없는 역할을 지정하면 빈 결과다.
 * roleName 을 생략하면 토큰의 모든 역할을 합치되 같은 호스트는 한 번만 센다.
 * 사용률은 Thanos 기간 평균({@link HostMetricsQueryService})이며 node_exporter instance 라벨로 조인한다.
 * 왼쪽 트리 구성은 ResourceExplorerTreeService 가 담당한다.
 */
@Service
public class ResourceExplorerMdcService {

    /** 노드 목록의 "현재" 사용률 집계 윈도우 */
    static final NodeMetricInterval LIST_INTERVAL = NodeMetricInterval.LAST_10M;

    private final ResourceScopeService scopeService;
    private final HostMetricsQueryService metricsQueryService;
    private final AuthService authService;

    public ResourceExplorerMdcService(ResourceScopeService scopeService,
                                      HostMetricsQueryService metricsQueryService,
                                      AuthService authService) {
        this.scopeService = scopeService;
        this.metricsQueryService = metricsQueryService;
        this.authService = authService;
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** MDC KPI 카드 — 물리 호스트 수 / Running / Pending / Stop / Error */
    public MdcSummaryResponse getMdcSummary(String roleName, String mdcId) {
        List<ScopedHost> hosts = scopedHosts(roleName, mdcId);
        return new MdcSummaryResponse(
                hosts.size(),
                countStatus(hosts, NodeStatus.RUNNING),
                countStatus(hosts, NodeStatus.PENDING),
                countStatus(hosts, NodeStatus.STOP),
                countStatus(hosts, NodeStatus.ERROR));
    }

    /** MDC 내 노드 Top-N — 요청 지표의 interval 기간 평균 내림차순, CPU/MEM/FS 보조 지표 동봉.
     *  요청 지표가 미수집인 호스트는 제외한다 — 남는 슬롯은 화면이 "미수집"으로 채운다. */
    public List<NodeTopResponse> getMdcTop(String roleName, String mdcId,
                                           NodeMetric metric, NodeMetricInterval interval, int limit) {
        List<ScopedHost> hosts = scopedHosts(roleName, mdcId);
        if (hosts.isEmpty()) {
            return List.of();
        }
        Map<String, HostUtilization> utilization = metricsQueryService.utilizationByInstance(interval);
        return hosts.stream()
                .map(h -> topRow(h, utilizationOf(h, utilization), metric))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(NodeTopResponse::value).reversed())
                .limit(limit)
                .toList();
    }

    /** MDC 내 노드 목록 — 상태·검색(필드 지정) 필터, 컬럼 정렬, 페이징(1부터). 사용률은 최근 10분 평균 */
    public PageResponse<NodeListItemResponse> getMdcNodes(
            String roleName,
            String mdcId,
            NodeStatus status,
            NodeSearchField searchField,
            String keyword,
            int page,
            int size,
            String sort) {
        Comparator<NodeListItemResponse> comparator = comparator(sort);   // 미지원 필드는 조회 전에 400
        List<ScopedHost> hosts = scopedHosts(roleName, mdcId).stream()
                .filter(h -> status == null || nodeStatus(h) == status)
                .toList();
        Map<String, HostUtilization> utilization = hosts.isEmpty()
                ? Map.of() : metricsQueryService.utilizationByInstance(LIST_INTERVAL);

        List<NodeListItemResponse> rows = hosts.stream()
                .map(h -> nodeRow(h, utilizationOf(h, utilization)))
                .filter(row -> matchesKeyword(row, searchField, keyword))
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    // ── 스코프 ──────────────────────────────────────────────────

    /** 사용자 토큰 역할 스코프의 물리 호스트에 roleName/mdcId 필터를 적용한다. roleName 생략 시 자산 ID 기준 중복 제거. */
    private List<ScopedHost> scopedHosts(String roleName, String mdcId) {
        Set<String> userRoles = authService.currentUserRoleIds();
        Collection<String> roles;
        if (roleName == null) {
            roles = userRoles;
        } else if (userRoles.contains(roleName)) {
            roles = List.of(roleName);
        } else {
            return List.of();
        }
        if (roles.isEmpty()) {
            return List.of();
        }
        return scopeService.scopeOf(roles).distinctHosts().stream()
                .filter(h -> mdcId == null || mdcId.equals(h.mdcId()))
                .toList();
    }

    // ── 행 구성 ─────────────────────────────────────────────────

    /** node_exporter 가 없는 호스트는 instance 가 없어 사용률 전부 미수집 */
    private static HostUtilization utilizationOf(ScopedHost host, Map<String, HostUtilization> utilization) {
        if (host.instance() == null) {
            return HostUtilization.EMPTY;
        }
        return utilization.getOrDefault(host.instance(), HostUtilization.EMPTY);
    }

    /** Top-N 1행 — 요청 지표가 미수집이면 null 을 반환해 목록에서 제외한다. */
    private static NodeTopResponse topRow(ScopedHost host, HostUtilization u, NodeMetric metric) {
        Double value = switch (metric) {
            case CPU -> u.cpuUtilization();
            case MEMORY -> u.memoryUtilization();
            case FILE_SYSTEM -> u.fileSystemUtilization();
        };
        if (value == null) {
            return null;
        }
        return new NodeTopResponse(
                String.valueOf(host.assetId()), host.name(), host.alias(), nodeStatus(host),
                value, u.cpuUtilization(), u.memoryUtilization(), u.fileSystemUtilization(),
                u.fileSystemFreeGb(), u.fileSystemTotalGb());
    }

    private static NodeListItemResponse nodeRow(ScopedHost host, HostUtilization u) {
        return new NodeListItemResponse(
                String.valueOf(host.assetId()),
                host.name(),
                host.alias(),
                nodeStatus(host),
                host.ipAddresses(),
                u.cpuUtilization(),
                u.memoryUtilization(),
                u.fileSystemUtilization());
    }

    /** 스코프 서비스의 4종 상태(ResourceStatus)와 노드 패널 상태(NodeStatus)는 값이 같다 */
    private static NodeStatus nodeStatus(ScopedHost host) {
        return NodeStatus.valueOf(host.status().name());
    }

    private static long countStatus(List<ScopedHost> hosts, NodeStatus status) {
        return hosts.stream().filter(h -> nodeStatus(h) == status).count();
    }

    /** searchField 가 지정한 필드에서만 keyword 를 찾는다 (화면의 "이름/별칭/IP" 드롭다운) */
    private static boolean matchesKeyword(NodeListItemResponse row, NodeSearchField searchField, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        return switch (searchField) {
            case NAME -> row.name().toLowerCase(Locale.ROOT).contains(lower);
            case ALIAS -> row.alias() != null && row.alias().toLowerCase(Locale.ROOT).contains(lower);
            case IP -> row.ipAddresses().stream().anyMatch(ip -> ip.contains(lower));
        };
    }

    /** sort 파라미터("{필드},{asc|desc}")를 Comparator 로 해석한다. 미지원 필드는 400, 미수집(null)은 마지막. */
    private static Comparator<NodeListItemResponse> comparator(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        Comparator<NodeListItemResponse> comparator = switch (field) {
            case "name" -> Comparator.comparing(NodeListItemResponse::name, String.CASE_INSENSITIVE_ORDER);
            case "alias" -> nullsLastComparator(NodeListItemResponse::alias, desc);
            case "status" -> Comparator.comparing(row -> row.status().name());
            case "ipAddress" -> nullsLastComparator(
                    row -> row.ipAddresses().isEmpty() ? null : row.ipAddresses().get(0), desc);
            case "cpuUtilization" -> nullsLastComparator(NodeListItemResponse::cpuUtilization, desc);
            case "memoryUtilization" -> nullsLastComparator(NodeListItemResponse::memoryUtilization, desc);
            case "fileSystemUtilization" -> nullsLastComparator(NodeListItemResponse::fileSystemUtilization, desc);
            default -> throw BusinessException.badRequest("지원하지 않는 정렬 필드입니다: " + field);
        };
        return desc ? comparator.reversed() : comparator;
    }

    /** 값 없음(null — 별칭 없음/미수집)이 오름/내림 어느 방향에서도 항상 마지막에 오는 비교자.
     *  desc 면 바깥에서 reversed() 되어 null 위치도 뒤집히므로, 미리 nullsFirst 로 두어 상쇄한다. */
    private static <T extends Comparable<T>> Comparator<NodeListItemResponse> nullsLastComparator(
            Function<NodeListItemResponse, T> extractor, boolean desc) {
        Comparator<T> nullHandling = desc
                ? Comparator.nullsFirst(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.naturalOrder());
        return Comparator.comparing(extractor, nullHandling);
    }
}

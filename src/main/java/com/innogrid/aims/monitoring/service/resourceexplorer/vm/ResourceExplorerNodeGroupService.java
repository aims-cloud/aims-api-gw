package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.util.ListSort;
import com.innogrid.aims.infra.entity.RoleNamespace;
import com.innogrid.aims.infra.repository.RoleNamespaceRepository;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeSearchField;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.VmListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.VmTopResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService.HostUtilization;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugStatusMapper;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbInfra;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbNode;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.TumblebugTreeBuilder.TbSnapshot;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 NodeGroup 패널 조회 서비스 — 트리의 Tumblebug Infra 하위 NodeGroup(또는 미분류) 선택 시
 * 개요 탭의 KPI 카드·VM Top-N·VM 목록을 제공한다.
 *
 * <ul>
 *   <li>스코프: nsId 가 토큰 역할(roleName 지정 시 그 역할)의 role_namespaces 매핑에 있어야 한다. 없으면 빈 결과.</li>
 *   <li>VM 목록: 트리와 같은 Tumblebug 스냅샷({@link TumblebugTreeBuilder#snapshot}). nodeGroupId 생략 시 Infra 전체,
 *       {@code @unclassified} 면 nodeGroupId 가 없는 Node 만.</li>
 *   <li>상태: 카드·필터는 §6.1 버킷, 목록 행에는 Tumblebug 원문도 함께 싣는다.</li>
 *   <li>사용률: Thanos 를 tb_ns/tb_infra/tb_node 라벨로 조인한 기간 평균({@link HostMetricsQueryService#utilizationByTumblebugNode}).</li>
 * </ul>
 * MDC 패널(물리 호스트)과 동형 구조다.
 */
@Service
public class ResourceExplorerNodeGroupService {

    /** sort 파라미터("{필드},{asc|desc}")를 Comparator 로 해석한다. 미지원 필드는 400, 미수집(null)은 마지막. */
    private static final ListSort.Spec<VmListItemResponse> SORT = ListSort.spec(
            Map.ofEntries(
                    Map.entry("name", ListSort.by(VmListItemResponse::name, String.CASE_INSENSITIVE_ORDER)),
                    Map.entry("status", ListSort.by(row -> row.status().name())),
                    Map.entry("tumblebugStatus", ListSort.by(VmListItemResponse::tumblebugStatus)),
                    Map.entry("provider", ListSort.by(VmListItemResponse::provider)),
                    Map.entry("ipAddress", ListSort.by(row -> row.ipAddresses().isEmpty() ? null : row.ipAddresses().get(0))),
                    Map.entry("cpuUtilization", ListSort.by(VmListItemResponse::cpuUtilization)),
                    Map.entry("memoryUtilization", ListSort.by(VmListItemResponse::memoryUtilization)),
                    Map.entry("fileSystemUtilization", ListSort.by(VmListItemResponse::fileSystemUtilization))),
            Comparator.comparing(VmListItemResponse::name, String.CASE_INSENSITIVE_ORDER));

    /** VM 목록의 "현재" 사용률 집계 윈도우 */
    static final NodeMetricInterval LIST_INTERVAL = NodeMetricInterval.LAST_10M;

    private final AuthService authService;
    private final RoleNamespaceRepository roleNamespaceRepository;
    private final TumblebugTreeBuilder tumblebugTreeBuilder;
    private final HostMetricsQueryService metricsQueryService;

    public ResourceExplorerNodeGroupService(AuthService authService,
                                            RoleNamespaceRepository roleNamespaceRepository,
                                            TumblebugTreeBuilder tumblebugTreeBuilder,
                                            HostMetricsQueryService metricsQueryService) {
        this.authService = authService;
        this.roleNamespaceRepository = roleNamespaceRepository;
        this.tumblebugTreeBuilder = tumblebugTreeBuilder;
        this.metricsQueryService = metricsQueryService;
    }

    /** 스코프 안의 Tumblebug Node 1개 — 조인 키와 상태 버킷을 미리 계산 */
    record ScopedTbNode(String nsId, String infraId, TbNode node, String vmId, String metricKey, NodeStatus status) {}

    // ── 공개 API ────────────────────────────────────────────────

    /** NodeGroup KPI 카드 — VM 수 / Running / Pending / Stop / Error */
    public NodeGroupSummaryResponse getNodeGroupSummary(String roleName, String nsId, String infraId, String nodeGroupId) {
        List<ScopedTbNode> nodes = scopedNodes(roleName, nsId, infraId, nodeGroupId);
        return new NodeGroupSummaryResponse(
                nodes.size(),
                count(nodes, NodeStatus.RUNNING),
                count(nodes, NodeStatus.PENDING),
                count(nodes, NodeStatus.STOP),
                count(nodes, NodeStatus.ERROR));
    }

    /** NodeGroup 내 VM Top-N — 요청 지표의 interval 기간 평균 내림차순, CPU/MEM/FS 보조 지표 동봉. 미수집 VM 은 제외 */
    public List<VmTopResponse> getNodeGroupTop(String roleName, String nsId, String infraId, String nodeGroupId,
                                               NodeMetric metric, NodeMetricInterval interval, int limit) {
        List<ScopedTbNode> nodes = scopedNodes(roleName, nsId, infraId, nodeGroupId);
        if (nodes.isEmpty()) {
            return List.of();
        }
        Map<String, HostUtilization> utilization = metricsQueryService.utilizationByTumblebugNode(interval);
        return nodes.stream()
                .map(n -> topRow(n, utilization.getOrDefault(n.metricKey(), HostUtilization.EMPTY), metric))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(VmTopResponse::value).reversed())
                .limit(limit)
                .toList();
    }

    /** NodeGroup 내 VM 목록 — 상태·검색(NAME|IP) 필터, 컬럼 정렬, 페이징(1부터). 사용률은 최근 10분 평균 */
    public PageResponse<VmListItemResponse> getNodeGroupVms(String roleName, String nsId, String infraId, String nodeGroupId,
                                                            NodeStatus status, NodeSearchField searchField, String keyword,
                                                            int page, int size, String sort) {
        Comparator<VmListItemResponse> comparator = SORT.comparator(sort);       // 미지원 필드는 조회 전에 400
        List<ScopedTbNode> nodes = scopedNodes(roleName, nsId, infraId, nodeGroupId).stream()
                .filter(n -> status == null || n.status() == status)
                .toList();
        Map<String, HostUtilization> utilization = nodes.isEmpty()
                ? Map.of() : metricsQueryService.utilizationByTumblebugNode(LIST_INTERVAL);

        List<VmListItemResponse> rows = nodes.stream()
                .map(n -> listRow(n, utilization.getOrDefault(n.metricKey(), HostUtilization.EMPTY)))
                .filter(row -> matchesKeyword(row, searchField, keyword))
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    // ── 스코프 ──────────────────────────────────────────────────

    /**
     * 토큰 역할(roleName 지정 시 그 역할)에 매핑된 Namespace 인지 확인하고, Infra 의 Node 를 nodeGroupId 로 걸러 돌려준다.
     * 스코프 밖이거나 Infra 가 없으면 빈 목록.
     */
    private List<ScopedTbNode> scopedNodes(String roleName, String nsId, String infraId, String nodeGroupId) {
        Set<String> userRoles = authService.currentUserRoleIds();
        List<String> roles = roleName == null ? new ArrayList<>(userRoles)
                : userRoles.contains(roleName) ? List.of(roleName) : List.of();
        if (roles.isEmpty()) {
            return List.of();
        }
        boolean mapped = roleNamespaceRepository.findByRole_NameIn(roles).stream()
                .map(RoleNamespace::getNsId)
                .anyMatch(nsId::equals);
        if (!mapped) {
            return List.of();
        }

        TbSnapshot tb = tumblebugTreeBuilder.snapshot(Set.of(nsId));
        TbInfra infra = tb.infrasByNamespace().getOrDefault(nsId, List.of()).stream()
                .filter(i -> i.id().equals(infraId))
                .findFirst()
                .orElse(null);
        if (infra == null) {
            return List.of();
        }
        return infra.nodes().stream()
                .filter(n -> matchesGroup(n, nodeGroupId))
                .map(n -> new ScopedTbNode(nsId, infraId, n,
                        "TB:" + nsId + "/" + infraId + "/" + n.id(),
                        HostMetricsQueryService.tumblebugKey(nsId, infraId, n.id()),
                        NodeStatus.valueOf(TumblebugStatusMapper.bucket(n.status()).name())))
                .toList();
    }

    /** nodeGroupId 생략 → 전체, {@code @unclassified} → 그룹 없는 Node, 그 외 → 정확히 일치 */
    static boolean matchesGroup(TbNode node, String nodeGroupId) {
        if (nodeGroupId == null || nodeGroupId.isBlank()) {
            return true;
        }
        boolean ungrouped = node.nodeGroupId() == null || node.nodeGroupId().isBlank();
        if (TumblebugTreeBuilder.UNCLASSIFIED_KEY.equals(nodeGroupId)) {
            return ungrouped;
        }
        return !ungrouped && node.nodeGroupId().equals(nodeGroupId);
    }

    // ── 행 구성 ─────────────────────────────────────────────────

    private static VmTopResponse topRow(ScopedTbNode n, HostUtilization u, NodeMetric metric) {
        Double value = switch (metric) {
            case CPU -> u.cpuUtilization();
            case MEMORY -> u.memoryUtilization();
            case FILE_SYSTEM -> u.fileSystemUtilization();
        };
        if (value == null) {
            return null;
        }
        return new VmTopResponse(n.vmId(), n.node().name(), n.status(), n.node().status(),
                value, u.cpuUtilization(), u.memoryUtilization(), u.fileSystemUtilization(),
                u.fileSystemFreeGb(), u.fileSystemTotalGb());
    }

    private static VmListItemResponse listRow(ScopedTbNode n, HostUtilization u) {
        List<String> ips = new ArrayList<>();
        if (n.node().publicIp() != null) {
            ips.add(n.node().publicIp());
        }
        if (n.node().privateIp() != null) {
            ips.add(n.node().privateIp());
        }
        return new VmListItemResponse(n.vmId(), n.node().name(), n.status(), n.node().status(), n.node().provider(),
                ips, u.cpuUtilization(), u.memoryUtilization(), u.fileSystemUtilization(),
                u.fileSystemFreeGb(), u.fileSystemTotalGb());
    }

    private static long count(List<ScopedTbNode> nodes, NodeStatus status) {
        return nodes.stream().filter(n -> n.status() == status).count();
    }

    /** searchField 가 지정한 필드에서만 keyword 를 찾는다. Tumblebug VM 은 별칭이 없어 ALIAS 는 항상 불일치 */
    private static boolean matchesKeyword(VmListItemResponse row, NodeSearchField searchField, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        return switch (searchField) {
            case NAME -> row.name().toLowerCase(Locale.ROOT).contains(lower);
            case ALIAS -> false;
            case IP -> row.ipAddresses().stream().anyMatch(ip -> ip.contains(lower));
        };
    }

    /** 값 없음(null)이 오름/내림 어느 방향에서도 항상 마지막에 오는 비교자 (목록 공통 규칙) */
    private static <T extends Comparable<T>> Comparator<VmListItemResponse> nullsLast(
            Function<VmListItemResponse, T> extractor, boolean desc) {
        Comparator<T> nullHandling = desc
                ? Comparator.nullsFirst(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.naturalOrder());
        return Comparator.comparing(extractor, nullHandling);
    }
}

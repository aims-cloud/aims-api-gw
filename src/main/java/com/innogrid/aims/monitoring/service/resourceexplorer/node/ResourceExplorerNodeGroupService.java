package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.mock.MockDataProvider;
import com.innogrid.aims.monitoring.ResourceExplorerProperties;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeSearchField;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeTopResponse;
import com.innogrid.aims.monitoring.model.ResourceExplorerTreeDataset;
import com.innogrid.aims.monitoring.model.ResourceExplorerTreeDataset.NodeInfo;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceExplorerTreeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 노드그룹 패널 조회 서비스.
 * 트리에서 배포영역별 노드그룹 선택 시 개요 탭의 KPI 카드·노드 Top-N·노드 목록을 제공한다.
 *
 * 조회 대상은 현재 사용자 토큰에 부여된 역할 스코프의 노드로 제한된다(트리와 동일).
 * roleId/deploymentId 파라미터는 스코프 안에서의 필터 — 토큰에 없는 역할을 지정하면 빈 결과다.
 * 왼쪽 트리 구성은 ResourceExplorerTreeService 가 담당한다.
 */
@Service
public class ResourceExplorerNodeGroupService {

    // TODO:: 자원 관리 데이터 연동 시 Mock 플래그 의존성 제거
    private final ResourceExplorerProperties properties;
    // TODO:: 자원 관리 데이터 연동 시 MockDataProvider 의존성을 실제 원장·Thanos 조회로 교체
    private final MockDataProvider mockDataProvider;
    // TODO:: AuthService 로 공통화
    private final ResourceExplorerTreeService resourceExplorerTreeService;

    public ResourceExplorerNodeGroupService(ResourceExplorerProperties properties,
                                            MockDataProvider mockDataProvider,
                                            ResourceExplorerTreeService resourceExplorerTreeService) {
        this.properties = properties;
        this.mockDataProvider = mockDataProvider;
        this.resourceExplorerTreeService = resourceExplorerTreeService;
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** 노드그룹 KPI 카드 — 노드 수 / Running / Stop / Error */
    public NodeGroupSummaryResponse getNodeGroupSummary(String roleId, String deploymentId) {
        List<NodeInfo> nodes = scopedNodes(roleId, deploymentId);
        return new NodeGroupSummaryResponse(
                nodes.size(),
                countStatus(nodes, NodeStatus.RUNNING),
                countStatus(nodes, NodeStatus.PENDING),
                countStatus(nodes, NodeStatus.STOP),
                countStatus(nodes, NodeStatus.ERROR));
    }

    /** 노드그룹 내 노드 Top-N — 요청 지표의 interval 기간 평균 내림차순, CPU/MEM/FS 보조 지표 동봉.
     *  요청 지표가 미수집인 노드는 제외한다 — 남는 슬롯은 화면이 "미수집"으로 채운다. */
    public List<NodeTopResponse> getNodeGroupTop(String roleId, String deploymentId,
                                                 NodeMetric metric, NodeMetricInterval interval, int limit) {
        return scopedNodes(roleId, deploymentId).stream()
                .map(n -> topRow(n, metric, interval))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(NodeTopResponse::value).reversed())
                .limit(limit)
                .toList();
    }

    /** 노드그룹 내 노드 목록 — 상태·검색(필드 지정) 필터, 컬럼 정렬, 페이징(1부터) */
    public PageResponse<NodeListItemResponse> getNodeGroupNodes(
            String roleId,
            String deploymentId,
            NodeStatus status,
            NodeSearchField searchField,
            String keyword,
            int page,
            int size,
            String sort) {
        List<NodeListItemResponse> rows = scopedNodes(roleId, deploymentId).stream()
                .filter(n -> status == null || status.name().equalsIgnoreCase(n.status()))
                .map(ResourceExplorerNodeGroupService::nodeRow)
                .filter(row -> matchesKeyword(row, searchField, keyword))
                .sorted(comparator(sort))
                .toList();
        return PageResponse.of(rows, page, size);
    }

    // ── 집계 내부 ───────────────────────────────────────────────

    /** 사용자 토큰 역할 스코프의 노드에 roleId/deploymentId 필터를 적용한다. */
    private List<NodeInfo> scopedNodes(String roleId, String deploymentId) {
        Set<String> userRoles = resourceExplorerTreeService.currentUserRoleIds();
        return loadDataset().nodes().stream()
                .filter(n -> userRoles.contains(n.roleId()))
                .filter(n -> roleId == null || n.roleId().equals(roleId))
                .filter(n -> deploymentId == null || n.deploymentId().equals(deploymentId))
                .toList();
    }

    private ResourceExplorerTreeDataset loadDataset() {
        // TODO:: 자원 관리 데이터(노드 원장)와 Thanos 지표 연동 시 Mock 분기를 실제 조회로 교체
        if (properties.isMockEnabled()) {
            return mockDataProvider.getResourceExplorerTreeDataset();
        }
        throw new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "노드 지표 데이터와 자원 탐색기 노드그룹 패널의 매핑 구현 전입니다.");
    }

    /** Top-N 1행 — 요청 지표가 미수집이면 null 을 반환해 목록에서 제외한다. */
    private static NodeTopResponse topRow(NodeInfo node, NodeMetric metric, NodeMetricInterval interval) {
        // TODO:: Thanos 연동 시 avg_over_time(...[interval.window()]) 평균 조회로 교체하고 Mock 감쇠 제거
        double factor = mockIntervalFactor(interval);
        Double cpu = scale(node.cpuUtilization(), factor);
        Double memory = scale(utilization(node.memoryUsedGb(), node.memoryTotalGb()), factor);
        Double fileSystem = scale(utilization(node.fileSystemUsedGb(), node.fileSystemTotalGb()), factor);
        Double value = switch (metric) {
            case CPU -> cpu;
            case MEMORY -> memory;
            case FILE_SYSTEM -> fileSystem;
        };
        if (value == null) {
            return null;
        }
        Double fileSystemFree = node.fileSystemUsedGb() == null || node.fileSystemTotalGb() == null
                ? null : node.fileSystemTotalGb() - node.fileSystemUsedGb();
        return new NodeTopResponse(
                node.nodeId(), node.name(), node.alias(),
                NodeStatus.valueOf(node.status().toUpperCase(Locale.ROOT)),
                value, cpu, memory, fileSystem,
                fileSystemFree,
                node.fileSystemTotalGb());
    }

    /** Mock 전용 — 기간이 길수록 평균이 완만해지는 것을 흉내 내는 감쇠 배율.
     *  TODO:: Thanos 평균 조회로 교체 시 제거 */
    private static double mockIntervalFactor(NodeMetricInterval interval) {
        return switch (interval) {
            case LAST_10M -> 1.0;
            case LAST_1H -> 0.95;
            case LAST_6H -> 0.85;
            case LAST_24H -> 0.7;
        };
    }

    private static NodeListItemResponse nodeRow(NodeInfo node) {
        return new NodeListItemResponse(
                node.nodeId(),
                node.name(),
                node.alias(),
                NodeStatus.valueOf(node.status().toUpperCase(Locale.ROOT)),
                node.ipAddresses(),
                node.cpuUtilization(),
                utilization(node.memoryUsedGb(), node.memoryTotalGb()),
                utilization(node.fileSystemUsedGb(), node.fileSystemTotalGb()));
    }

    /** 사용률(%) — 소수 1자리 반올림. 미수집(null)이면 null, 전체 용량이 0이면 0. */
    private static Double utilization(Double used, Double total) {
        if (used == null || total == null) {
            return null;
        }
        return total <= 0 ? 0.0 : round1(used / total * 100);
    }

    /** Mock 감쇠 배율 적용 — 미수집(null)은 그대로 null. TODO:: Thanos 평균 조회로 교체 시 제거 */
    private static Double scale(Double value, double factor) {
        return value == null ? null : round1(value * factor);
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static long countStatus(List<NodeInfo> nodes, NodeStatus status) {
        return nodes.stream().filter(n -> status.name().equalsIgnoreCase(n.status())).count();
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
            case "name" -> Comparator.comparing(NodeListItemResponse::name);
            case "alias" -> nullsLastComparator(NodeListItemResponse::alias, desc);
            case "status" -> Comparator.comparing(row -> row.status().name());
            case "ipAddress" -> nullsLastComparator(
                    row -> row.ipAddresses().isEmpty() ? null : row.ipAddresses().get(0), desc);
            case "cpuUtilization" -> nullsLastComparator(NodeListItemResponse::cpuUtilization, desc);
            case "memoryUtilization" -> nullsLastComparator(NodeListItemResponse::memoryUtilization, desc);
            case "fileSystemUtilization" -> nullsLastComparator(NodeListItemResponse::fileSystemUtilization, desc);
            default -> throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "지원하지 않는 정렬 필드입니다: " + field);
        };
        return desc ? comparator.reversed() : comparator;
    }

    /** 값 없음(null — 별칭 없음/미수집)이 오름/내림 어느 방향에서도 항상 마지막에 오는 비교자.
     *  desc 면 바깥에서 reversed() 되어 null 위치도 뒤집히므로, 미리 nullsFirst 로 두어 상쇄한다. */
    private static <T extends Comparable<T>> Comparator<NodeListItemResponse> nullsLastComparator(
            java.util.function.Function<NodeListItemResponse, T> extractor, boolean desc) {
        Comparator<T> nullHandling = desc
                ? Comparator.nullsFirst(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.naturalOrder());
        return Comparator.comparing(extractor, nullHandling);
    }
}

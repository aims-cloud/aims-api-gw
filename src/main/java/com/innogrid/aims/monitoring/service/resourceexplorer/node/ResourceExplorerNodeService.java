package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.mock.MockDataProvider;
import com.innogrid.aims.monitoring.ResourceExplorerProperties;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDailyUsageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse.CapacityMetricCard;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse.CpuCard;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse.GpuCard;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse.NetworkTrafficCard;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeVmListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeStatus;
import com.innogrid.aims.monitoring.model.ResourceExplorerTreeDataset;
import com.innogrid.aims.monitoring.model.ResourceExplorerTreeDataset.NodeInfo;
import com.innogrid.aims.monitoring.model.ResourceExplorerTreeDataset.NodeMetrics;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceExplorerTreeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자원 탐색기 노드 상세 패널 조회 서비스.
 * 트리에서 물리 노드 선택 시 개요(주요 지표 카드·요약 차트·정보 표)·가상머신 탭 데이터를 제공한다.
 *
 * 경로의 nodeId 가 현재 사용자 토큰의 역할 스코프에 없으면 404 —
 * 트리에 노출되지 않는 노드의 패널을 URL 로 직접 조회할 수 없다(역할 패널과 동일 정책).
 * 로그/트레이스 탭은 NodeLog/NodeTrace 서비스, 알람 탭은 AlarmHistory/AlarmPolicy API 를 재사용한다.
 */
@Service
public class ResourceExplorerNodeService {

    /** Mock 시계열 생성용 결정적 패턴 — 현재값 대비 배율 (하루 부하 곡선 흉내) */
    // TODO:: Thanos range 쿼리 연동 시 Mock 시계열 생성 로직 제거
    private static final double[] TREND_PATTERN = {
            0.55, 0.52, 0.50, 0.48, 0.47, 0.50, 0.58, 0.68,
            0.78, 0.86, 0.92, 0.95, 0.97, 1.00, 0.98, 0.94,
            0.90, 0.88, 0.85, 0.80, 0.74, 0.68, 0.62, 0.58};

    private static final int MAX_TREND_POINTS = 500;

    // TODO:: 자원 관리 데이터 연동 시 Mock 플래그 의존성 제거
    private final ResourceExplorerProperties properties;
    // TODO:: 자원 관리 데이터 연동 시 MockDataProvider 의존성을 실제 원장·Thanos 조회로 교체
    private final MockDataProvider mockDataProvider;
    // TODO:: AuthService 로 공통화
    private final ResourceExplorerTreeService resourceExplorerTreeService;

    public ResourceExplorerNodeService(ResourceExplorerProperties properties,
                                       MockDataProvider mockDataProvider,
                                       ResourceExplorerTreeService resourceExplorerTreeService) {
        this.properties = properties;
        this.mockDataProvider = mockDataProvider;
        this.resourceExplorerTreeService = resourceExplorerTreeService;
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** 개요 탭 주요 지표 카드 6종 — 미수집이거나 GPU 미탑재면 해당 카드가 null 로 내려간다 */
    public NodeSummaryResponse getNodeSummary(String nodeId) {
        NodeInfo node = accessibleNode(nodeId);
        NodeMetrics m = node.metrics();
        if (m == null) {
            return new NodeSummaryResponse(now(), null, null, null, null, null, null);
        }
        return new NodeSummaryResponse(
                now(),
                m.cpuUtilization() == null ? null : new CpuCard(m.cpuUtilization(), node.spec().cpuCoreCount()),
                capacityCard(m.memoryUsedGb(), m.memoryTotalGb()),
                capacityCard(m.fileSystemUsedGb(), m.fileSystemTotalGb()),
                m.networkReceiveBytesPerSec() == null || m.networkTransmitBytesPerSec() == null
                        ? null
                        : new NetworkTrafficCard(m.networkReceiveBytesPerSec(), m.networkTransmitBytesPerSec()),
                m.gpuUtilization() == null ? null : new GpuCard(m.gpuUtilization(), node.gpuIds().size()),
                capacityCard(m.vramUsedGb(), m.vramTotalGb()));
    }

    /** 개요 탭 모니터링 요약 차트 — 지표별 시계열 (NETWORK 는 수신/송신 2개 시리즈, 미수집이면 빈 시리즈).
     *  metric 미지정 시 전체 지표(6종)를 반환한다.
     *  조회 구간은 지금 기준 interval 기간(지금-window ~ 지금), 버킷 간격은 기간별 defaultStep 이다. */
    public List<NodeMetricTrendResponse> getNodeMetricTrend(String nodeId, NodeDetailMetric metric,
                                                            NodeMetricInterval interval) {
        NodeInfo node = accessibleNode(nodeId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<NodeDetailMetric> metrics = metric == null
                ? List.of(NodeDetailMetric.values())
                : List.of(metric);
        return metrics.stream()
                .map(m -> metricTrend(node, m, from, to, step))
                .toList();
    }

    /** 지표 1종의 시계열 응답을 구성한다 */
    private static NodeMetricTrendResponse metricTrend(NodeInfo node, NodeDetailMetric metric, Instant from, Instant to, String step) {
        NodeMetrics m = node.metrics();
        List<NodeMetricTrendResponse.Series> series = new ArrayList<>();
        if (m != null) {
            switch (metric) {
                case CPU -> addSeries(series, metric.name().toLowerCase(Locale.ROOT),
                        m.cpuUtilization(), from, to, step);
                case MEMORY -> addSeries(series, metric.name().toLowerCase(Locale.ROOT),
                        utilization(m.memoryUsedGb(), m.memoryTotalGb()), from, to, step);
                case FILE_SYSTEM -> addSeries(series, metric.name().toLowerCase(Locale.ROOT),
                        utilization(m.fileSystemUsedGb(), m.fileSystemTotalGb()), from, to, step);
                case NETWORK -> {
                    addSeries(series, "receive", m.networkReceiveBytesPerSec(), from, to, step);
                    addSeries(series, "transmit", m.networkTransmitBytesPerSec(), from, to, step);
                }
                case GPU -> addSeries(series, metric.name().toLowerCase(Locale.ROOT),
                        m.gpuUtilization(), from, to, step);
                case VRAM -> addSeries(series, metric.name().toLowerCase(Locale.ROOT),
                        utilization(m.vramUsedGb(), m.vramTotalGb()), from, to, step);
            }
        }
        return new NodeMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step, series);
    }

    /** 개요 탭 노드 정보 표 — 정적 스펙과 우상단 배지(provider/platformType) */
    public NodeInfoResponse getNodeInfo(String nodeId) {
        NodeInfo node = accessibleNode(nodeId);
        long vmCount = loadDataset().vms().stream()
                .filter(v -> nodeId.equals(v.nodeId()))
                .count();
        return new NodeInfoResponse(
                node.nodeId(),
                node.name(),
                String.join(",", node.ipAddresses()),
                node.spec().uptimeDays(),
                node.spec().type(),
                (int) vmCount,
                node.spec().cpuAllocatedCores(),
                node.spec().memoryAllocatedGb(),
                node.spec().storage(),
                node.spec().provider(),
                node.spec().platformType());
    }

    /** 가상머신 탭 — 이 노드에서 실행 중인 VM 목록, 상태·키워드(이름/별칭) 필터·정렬·페이징(1부터) */
    public PageResponse<NodeVmListItemResponse> getNodeVms(String nodeId, NodeStatus status,
                                                           String keyword, int page, int size, String sort) {
        // TODO:: 가상머신 탭 화면 설계 확정 시 컬럼·검색 필드 재정의
        accessibleNode(nodeId);
        List<NodeVmListItemResponse> rows = loadDataset().vms().stream()
                .filter(v -> nodeId.equals(v.nodeId()))
                .filter(v -> status == null || status.name().equalsIgnoreCase(v.status()))
                .map(v -> new NodeVmListItemResponse(
                        v.vmId(), v.name(), v.alias(),
                        NodeStatus.valueOf(v.status().toUpperCase(Locale.ROOT)),
                        v.ipAddresses(), v.cpuUtilization(), v.memoryUtilization(), v.diskUtilization()))
                .filter(row -> matchesKeyword(row, keyword))
                .sorted(comparator(sort))
                .toList();
        return PageResponse.of(rows, page, size);
    }

    /** 키워드는 이름/별칭에서 찾는다 */
    private static boolean matchesKeyword(NodeVmListItemResponse row, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        return row.name().toLowerCase(Locale.ROOT).contains(lower)
                || (row.alias() != null && row.alias().toLowerCase(Locale.ROOT).contains(lower));
    }

    /** 자원 사용 현황 탭 — 일자별 일간 최대 CPU/Memory/File System 사용률과 일간 최대 가상머신수.
     *  interval 기간(지금 기준)에 걸친 일자의 행을 반환한다. 값이 null 이면 해당 일자 미수집("-")이다. */
    public PageResponse<NodeDailyUsageResponse> getNodeUsage(String nodeId, NodeMetricInterval interval, int page, int size, String sort) {
        NodeInfo node = accessibleNode(nodeId);
        long vmCount = loadDataset().vms().stream()
                .filter(v -> nodeId.equals(v.nodeId()))
                .count();
        LocalDate from = LocalDate.now(ZoneId.systemDefault())
                .minusDays(Math.max(0, interval.window().toDays()));

        List<NodeDailyUsageResponse> rows = mockDailyUsage(node, vmCount).stream()
                .filter(row -> !row.date().isBefore(from))
                .sorted(usageComparator(sort))
                .toList();
        return PageResponse.of(rows, page, size);
    }

    /** Mock 일간 최대치 — 오늘/어제 2행을 현재 지표 기반으로 결정적으로 생성한다.
     *  어제의 가상머신수는 수집 이력이 없는 상태("-")를 흉내 내 null 이다.
     *  TODO:: Thanos max_over_time 일 단위 집계(또는 일 배치 적재) 연동 시 이 생성 로직 제거 */
    private static List<NodeDailyUsageResponse> mockDailyUsage(NodeInfo node, long vmCount) {
        NodeMetrics m = node.metrics();
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        return List.of(
                new NodeDailyUsageResponse(today,
                        dailyMax(m == null ? null : m.cpuUtilization(), 1.15),
                        dailyMax(m == null ? null : utilization(m.memoryUsedGb(), m.memoryTotalGb()), 1.1),
                        dailyMax(m == null ? null : utilization(m.fileSystemUsedGb(), m.fileSystemTotalGb()), 1.05),
                        m == null ? null : vmCount),
                new NodeDailyUsageResponse(today.minusDays(1),
                        dailyMax(m == null ? null : m.cpuUtilization(), 0.9),
                        dailyMax(m == null ? null : utilization(m.memoryUsedGb(), m.memoryTotalGb()), 0.95),
                        dailyMax(m == null ? null : utilization(m.fileSystemUsedGb(), m.fileSystemTotalGb()), 1.0),
                        null));
    }

    /** 일간 최대치 흉내 — 현재값 × 배율, 100% 상한. 미수집(null)은 그대로 null */
    private static Double dailyMax(Double current, double factor) {
        return current == null ? null : Math.min(100.0, round1(current * factor));
    }

    /** 자원 사용 현황 sort 해석 — 미지원 필드는 400, 미수집(null)은 마지막 */
    private static Comparator<NodeDailyUsageResponse> usageComparator(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        Comparator<NodeDailyUsageResponse> comparator = switch (field) {
            case "date" -> Comparator.comparing(NodeDailyUsageResponse::date);
            case "maxCpuUtilization" -> nullsLast(NodeDailyUsageResponse::maxCpuUtilization, desc);
            case "maxMemoryUtilization" -> nullsLast(NodeDailyUsageResponse::maxMemoryUtilization, desc);
            case "maxFileSystemUtilization" -> nullsLast(NodeDailyUsageResponse::maxFileSystemUtilization, desc);
            case "maxVmCount" -> nullsLast(NodeDailyUsageResponse::maxVmCount, desc);
            default -> throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "지원하지 않는 정렬 필드입니다: " + field);
        };
        return desc ? comparator.reversed() : comparator;
    }

    /** 값 없음(null)이 오름/내림 어느 방향에서도 항상 마지막에 오는 비교자 (목록 공통 규칙) */
    private static <T extends Comparable<T>> Comparator<NodeDailyUsageResponse> nullsLast(
            java.util.function.Function<NodeDailyUsageResponse, T> extractor, boolean desc) {
        Comparator<T> nullHandling = desc
                ? Comparator.nullsFirst(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.naturalOrder());
        return Comparator.comparing(extractor, nullHandling);
    }

    // ── 노드 접근 검증 ──────────────────────────────────────────

    /** nodeId 가 데이터셋에 존재하고 현재 사용자 토큰의 역할 스코프에 속할 때만 반환한다. 아니면 404. */
    private NodeInfo accessibleNode(String nodeId) {
        return loadDataset().nodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .filter(n -> resourceExplorerTreeService.currentUserRoleIds().contains(n.roleId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "노드를 찾을 수 없습니다: " + nodeId));
    }

    private ResourceExplorerTreeDataset loadDataset() {
        // TODO:: 자원 관리 데이터(노드 원장)와 Thanos 지표 연동 시 Mock 분기를 실제 조회로 교체
        if (properties.isMockEnabled()) {
            return mockDataProvider.getResourceExplorerTreeDataset();
        }
        throw new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "노드 지표·정보 데이터와 자원 탐색기 노드 상세 패널의 매핑 구현 전입니다.");
    }

    // ── Mock 시계열 생성 ────────────────────────────────────────

    /** 지표 현재값이 있으면 결정적 시계열 시리즈를 추가한다 — 미수집(null)이면 추가하지 않는다 */
    // TODO:: Thanos range 쿼리 연동 시 Mock 시계열 생성 로직 제거
    private static void addSeries(List<NodeMetricTrendResponse.Series> series,
                                  String name, Double baseValue, Instant from, Instant to, String step) {
        if (baseValue == null) {
            return;
        }
        long stepSeconds = parseStepSeconds(step);
        long span = Math.max(1, to.getEpochSecond() - from.getEpochSecond());
        long points = Math.min(span / stepSeconds + 1, MAX_TREND_POINTS);

        List<MetricTrendResponse.DataPoint> dataPoints = new ArrayList<>();
        for (long i = 0; i < points; i++) {
            long timestamp = from.getEpochSecond() + i * stepSeconds;
            double factor = TREND_PATTERN[(int) (i % TREND_PATTERN.length)];
            dataPoints.add(new MetricTrendResponse.DataPoint(timestamp, round1(baseValue * factor)));
        }
        series.add(new NodeMetricTrendResponse.Series(name, dataPoints));
    }

    /** step 문자열("300s", "5m", "1h")을 초로 해석한다. 형식이 잘못되면 400. */
    private static long parseStepSeconds(String step) {
        try {
            String trimmed = step.trim().toLowerCase(Locale.ROOT);
            long value = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
            long seconds = switch (trimmed.charAt(trimmed.length() - 1)) {
                case 's' -> value;
                case 'm' -> value * 60;
                case 'h' -> value * 3600;
                case 'd' -> value * 86400;
                default -> throw new NumberFormatException();
            };
            if (seconds <= 0) {
                throw new NumberFormatException();
            }
            return seconds;
        } catch (RuntimeException e) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "지원하지 않는 step 형식입니다 (예: 300s, 5m, 1h, 1d): " + step);
        }
    }

    // ── 집계 내부 ───────────────────────────────────────────────

    /** 사용률 + Free/Total 카드 — used/total 중 하나라도 미수집이면 카드 없음(null) */
    private static CapacityMetricCard capacityCard(Double usedGb, Double totalGb) {
        Double percent = utilization(usedGb, totalGb);
        if (percent == null) {
            return null;
        }
        return new CapacityMetricCard(percent, round1(totalGb - usedGb), totalGb);
    }

    /** 사용률(%) — 소수 1자리 반올림. 미수집(null)이면 null, 전체 용량이 0이면 0. */
    private static Double utilization(Double used, Double total) {
        if (used == null || total == null) {
            return null;
        }
        return total <= 0 ? 0.0 : round1(used / total * 100);
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneId.systemDefault());
    }

    /** sort 파라미터("{필드},{asc|desc}")를 Comparator 로 해석한다. 미지원 필드는 400, 미수집(null)은 마지막. */
    private static Comparator<NodeVmListItemResponse> comparator(String sort) {
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        Comparator<NodeVmListItemResponse> comparator = switch (field) {
            case "name" -> Comparator.comparing(NodeVmListItemResponse::name);
            case "alias" -> nullsLastComparator(NodeVmListItemResponse::alias, desc);
            case "status" -> Comparator.comparing(row -> row.status().name());
            case "ipAddress" -> nullsLastComparator(
                    row -> row.ipAddresses().isEmpty() ? null : row.ipAddresses().get(0), desc);
            case "cpuUtilization" -> nullsLastComparator(NodeVmListItemResponse::cpuUtilization, desc);
            case "memoryUtilization" -> nullsLastComparator(NodeVmListItemResponse::memoryUtilization, desc);
            case "diskUtilization" -> nullsLastComparator(NodeVmListItemResponse::diskUtilization, desc);
            default -> throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "지원하지 않는 정렬 필드입니다: " + field);
        };
        return desc ? comparator.reversed() : comparator;
    }

    /** 값 없음(null)이 오름/내림 어느 방향에서도 항상 마지막에 오는 비교자 (노드그룹 목록과 동일 규칙) */
    private static <T extends Comparable<T>> Comparator<NodeVmListItemResponse> nullsLastComparator(
            java.util.function.Function<NodeVmListItemResponse, T> extractor, boolean desc) {
        Comparator<T> nullHandling = desc
                ? Comparator.nullsFirst(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.naturalOrder());
        return Comparator.comparing(extractor, nullHandling);
    }
}

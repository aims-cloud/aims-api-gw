package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.util.ListSort;
import com.innogrid.aims.common.util.MetricFormat;
import com.innogrid.aims.infra.entity.Asset;
import com.innogrid.aims.infra.repository.AssetRepository;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;
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
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceScopeService.ScopedHost;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDetailMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDetailMetricsQueryService.DailyMax;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostDetailMetricsQueryService.HostSummaryMetrics;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostMetricsQueryService.HostUtilization;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.Sebu1VirtualResourceProvider;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.Sebu1VirtualResourceProvider.VirtualResource;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드(물리 호스트) 상세 패널 조회 서비스.
 * 트리에서 물리 호스트 선택 시 개요(주요 지표 카드·요약 차트·정보 표)·가상머신·자원 사용 현황 탭 데이터를 제공한다.
 *
 * nodeId 는 assets.id 다. 현재 사용자 토큰의 역할 스코프({@link ResourceScopeService})에 없으면 404 —
 * 트리에 노출되지 않는 노드의 패널을 URL 로 직접 조회할 수 없다(역할·MDC 패널과 동일 정책).
 * 지표는 Thanos({@link HostDetailMetricsQueryService}), 가상머신은 1세부 Host↔VM 관계({@link Sebu1VirtualResourceProvider})다.
 * 로그/트레이스 탭은 NodeLog/NodeTrace 서비스, 알람 탭은 AlarmHistory/AlarmPolicy API 를 재사용한다.
 */
@Service
public class ResourceExplorerNodeService {

    /** 자원 사용 현황 sort 해석 — 미지원 필드는 400, 미수집(null)은 마지막 */
    private static final ListSort.Spec<NodeDailyUsageResponse> USAGE_SORT = ListSort.spec(
            Map.ofEntries(
                    Map.entry("date", ListSort.by(NodeDailyUsageResponse::date)),
                    Map.entry("maxCpuUtilization", ListSort.by(NodeDailyUsageResponse::maxCpuUtilization)),
                    Map.entry("maxMemoryUtilization", ListSort.by(NodeDailyUsageResponse::maxMemoryUtilization)),
                    Map.entry("maxFileSystemUtilization", ListSort.by(NodeDailyUsageResponse::maxFileSystemUtilization)),
                    Map.entry("maxVmCount", ListSort.by(NodeDailyUsageResponse::maxVmCount))),
            null);

    /** 가상머신 탭 sort 해석 — 미지원 필드는 400, 미수집(null)은 마지막 */
    private static final ListSort.Spec<NodeVmListItemResponse> VM_SORT = ListSort.spec(
            Map.ofEntries(
                    Map.entry("name", ListSort.by(NodeVmListItemResponse::name, String.CASE_INSENSITIVE_ORDER)),
                    Map.entry("alias", ListSort.by(NodeVmListItemResponse::alias)),
                    Map.entry("status", ListSort.by(row -> row.status().name())),
                    Map.entry("ipAddress", ListSort.by(row -> row.ipAddresses().isEmpty() ? null : row.ipAddresses().get(0))),
                    Map.entry("cpuUtilization", ListSort.by(NodeVmListItemResponse::cpuUtilization)),
                    Map.entry("memoryUtilization", ListSort.by(NodeVmListItemResponse::memoryUtilization)),
                    Map.entry("diskUtilization", ListSort.by(NodeVmListItemResponse::diskUtilization))),
            null);

    /** 가상머신 탭 사용률의 집계 윈도우 */
    static final NodeMetricInterval VM_LIST_INTERVAL = NodeMetricInterval.LAST_10M;

    private final NodeAccessService nodeAccess;
    private final Sebu1VirtualResourceProvider virtualResourceProvider;
    private final HostDetailMetricsQueryService detailMetrics;
    private final HostMetricsQueryService hostMetrics;

    public ResourceExplorerNodeService(NodeAccessService nodeAccess,
                                       Sebu1VirtualResourceProvider virtualResourceProvider,
                                       HostDetailMetricsQueryService detailMetrics,
                                       HostMetricsQueryService hostMetrics) {
        this.nodeAccess = nodeAccess;
        this.virtualResourceProvider = virtualResourceProvider;
        this.detailMetrics = detailMetrics;
        this.hostMetrics = hostMetrics;
    }

    /** 접근 검증을 통과한 노드 — 자산, 스코프 호스트, exporter 선택자(instance 기준, 없으면 null) */
    record NodeContext(Asset asset, ScopedHost host, MetricSelector node, MetricSelector dcgm) {}

    // ── 공개 API ────────────────────────────────────────────────

    /** 개요 탭 주요 지표 카드 6종 — 미수집이거나 GPU 미탑재면 해당 카드가 null 로 내려간다 */
    public NodeSummaryResponse getNodeSummary(String nodeId) {
        NodeContext ctx = accessibleNode(nodeId);
        HostSummaryMetrics m = detailMetrics.summary(ctx.node(), ctx.dcgm());
        return new NodeSummaryResponse(
                now(),
                m.cpuPercent() == null ? null : new CpuCard(m.cpuPercent(), zeroIfNull(m.coreCount())),
                capacityCard(m.memoryUsedGb(), m.memoryTotalGb()),
                capacityCard(m.fileSystemUsedGb(), m.fileSystemTotalGb()),
                m.receiveBytesPerSec() == null || m.transmitBytesPerSec() == null
                        ? null : new NetworkTrafficCard(m.receiveBytesPerSec(), m.transmitBytesPerSec()),
                m.gpuPercent() == null ? null : new GpuCard(m.gpuPercent(), zeroIfNull(m.gpuCount())),
                capacityCard(m.vramUsedGb(), m.vramTotalGb()));
    }

    /** 개요 탭 모니터링 요약 차트 — 지표별 시계열 (NETWORK 는 수신/송신 2개 시리즈, 미수집이면 빈 시리즈).
     *  metric 미지정 시 전체 지표(6종)를 반환한다.
     *  조회 구간은 지금 기준 interval 기간(지금-window ~ 지금), 버킷 간격은 기간별 defaultStep 이다. */
    public List<NodeMetricTrendResponse> getNodeMetricTrend(String nodeId, NodeDetailMetric metric,
                                                            NodeMetricInterval interval) {
        NodeContext ctx = accessibleNode(nodeId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<NodeDetailMetric> metrics = metric == null ? List.of(NodeDetailMetric.values()) : List.of(metric);
        return metrics.stream()
                .map(m -> new NodeMetricTrendResponse(m, m.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                        detailMetrics.trend(m, ctx.node(), ctx.dcgm(), from, to, step)))
                .toList();
    }

    /**
     * 개요 탭 노드 정보 표 — 자산 원장(유형·제조사·시스템 유형)과 Thanos 현재값(가동일수·코어·메모리·스토리지), 1세부 VM 수.
     * provider 배지는 자산 제조사, platformType 은 자산 시스템 유형으로 채운다.
     */
    public NodeInfoResponse getNodeInfo(String nodeId) {
        NodeContext ctx = accessibleNode(nodeId);
        HostSummaryMetrics m = detailMetrics.summary(ctx.node(), ctx.dcgm());
        int vmCount = virtualResourceProvider.findByHost(ctx.asset()).size();
        return new NodeInfoResponse(
                nodeId,
                ctx.asset().getName(),
                String.join(",", ctx.host().ipAddresses()),
                uptimeDays(m.bootTimeSeconds()),
                ctx.asset().getType().name(),
                vmCount,
                zeroIfNull(m.coreCount()),
                m.memoryTotalGb() == null ? 0.0 : m.memoryTotalGb(),
                m.fileSystemTotalGb() == null ? null : m.fileSystemTotalGb() + " GB",
                ctx.asset().getManufacturer(),
                ctx.asset().getSystemType());
    }

    /** 가상머신 탭 — 이 물리 호스트에 배치된 1세부 VM 목록, 상태·키워드(이름/별칭) 필터·정렬·페이징(1부터).
     *  사용률은 VM 내부 node_exporter 의 최근 10분 평균, exporter 가 없으면 미수집(null). */
    public PageResponse<NodeVmListItemResponse> getNodeVms(String nodeId, NodeStatus status,
                                                           String keyword, int page, int size, String sort) {
        Comparator<NodeVmListItemResponse> comparator = VM_SORT.comparator(sort);   // 미지원 필드는 조회 전에 400
        NodeContext ctx = accessibleNode(nodeId);
        List<VirtualResource> vms = virtualResourceProvider.findByHost(ctx.asset());
        Map<String, HostUtilization> utilization = vms.stream().anyMatch(v -> v.instance() != null)
                ? hostMetrics.utilizationByInstance(VM_LIST_INTERVAL) : Map.of();

        List<NodeVmListItemResponse> rows = vms.stream()
                .map(v -> vmRow(v, v.instance() == null
                        ? HostUtilization.EMPTY : utilization.getOrDefault(v.instance(), HostUtilization.EMPTY)))
                .filter(row -> status == null || row.status() == status)
                .filter(row -> matchesKeyword(row, keyword))
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    /** 자원 사용 현황 탭 — 일자별 일간 최대 CPU/Memory/File System 사용률과 가상머신수.
     *  interval 기간(지금 기준)에 걸친 일자의 행을 반환한다. 값이 null 이면 해당 일자 미수집("-")이다.
     *  가상머신수는 이력이 없어 오늘 행에만 현재 1세부 VM 수를 싣는다. */
    // TODO:: 1세부 VM 배치 이력(일간 최대 VM 수)이 제공되면 과거 일자의 maxVmCount 채우기
    public PageResponse<NodeDailyUsageResponse> getNodeUsage(String nodeId, NodeMetricInterval interval,
                                                             int page, int size, String sort) {
        Comparator<NodeDailyUsageResponse> comparator = USAGE_SORT.comparator(sort);   // 미지원 필드는 조회 전에 400
        NodeContext ctx = accessibleNode(nodeId);
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate from = today.minusDays(Math.max(0, interval.window().toDays()));
        long vmCount = virtualResourceProvider.findByHost(ctx.asset()).size();

        List<NodeDailyUsageResponse> rows = detailMetrics.dailyMax(ctx.node(), from, today).stream()
                .map(d -> new NodeDailyUsageResponse(d.date(), d.cpuPercent(), d.memoryPercent(), d.fileSystemPercent(),
                        d.date().equals(today) ? vmCount : null))
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    // ── 노드 접근 검증 ──────────────────────────────────────────

    /** nodeId(assets.id)가 현재 사용자 토큰의 역할 스코프에 있는 물리 호스트일 때만 컨텍스트를 만든다. 아니면 404. */
    private NodeContext accessibleNode(String nodeId) {
        NodeAccessService.AccessibleNode accessible = nodeAccess.require(nodeId);
        ScopedHost host = accessible.host();
        Asset asset = accessible.asset();
        return new NodeContext(asset, host, NodeAccessService.selectorOf(host), nodeAccess.dcgmSelectorOf(host));
    }

    // ── 행·카드 구성 ────────────────────────────────────────────

    private static NodeVmListItemResponse vmRow(VirtualResource vm, HostUtilization u) {
        return new NodeVmListItemResponse(
                "SEBU1:" + vm.externalId(),
                vm.name(),
                null,                                                   // 1세부 VM 별칭 없음
                NodeStatus.valueOf(ResourceScopeService.sebu1Status(vm.status()).name()),
                List.of(),                                              // TODO:: 1세부 VM IP 제공 시 채우기
                u.cpuUtilization(),
                u.memoryUtilization(),
                u.fileSystemUtilization());
    }

    /** 사용률 + Free/Total 카드 — used/total 중 하나라도 미수집이면 카드 없음(null) */
    private static CapacityMetricCard capacityCard(Double usedGb, Double totalGb) {
        if (usedGb == null || totalGb == null) {
            return null;
        }
        double percent = totalGb <= 0 ? 0.0 : MetricFormat.round1(usedGb / totalGb * 100);
        return new CapacityMetricCard(percent, MetricFormat.round1(totalGb - usedGb), totalGb);
    }

    private static long uptimeDays(Long bootTimeSeconds) {
        if (bootTimeSeconds == null) {
            return 0;
        }
        return Math.max(0, (Instant.now().getEpochSecond() - bootTimeSeconds) / 86_400);
    }

    private static int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneId.systemDefault());
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

}

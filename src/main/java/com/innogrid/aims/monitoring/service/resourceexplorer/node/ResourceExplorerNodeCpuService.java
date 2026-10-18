package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.common.util.ListSort;
import com.innogrid.aims.common.util.MetricFormat;
import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService.CpuDevice;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostCategoryMetricsQueryService.CpuInventory;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 CPU 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 CPU 항목 선택 시 개요(KPI 카드·CPU 목록)와 모니터링(mode 별 차트 9종) 탭 데이터를 제공한다.
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드의 node_exporter 를 {@code instance} 라벨로 좁혀 읽는다({@link MetricSelector#instance}).
 * node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 미수집(null·빈 목록·빈 시리즈)으로 내린다.
 * KPI 카드와 CPU 목록의 원천은 VM CPU 패널과 같은 {@link HostCategoryMetricsQueryService} 다.
 */
@Service
public class ResourceExplorerNodeCpuService {

    /** sort 파라미터("{필드},{asc|desc}") — cpuId|socket|vendor|model|coreCount|threadCount|baseClockGhz, 동률·값 없음은 소켓순 */
    private static final ListSort.Spec<CpuDeviceListItemResponse> SORT = ListSort.spec(
            Map.ofEntries(
                    Map.entry("cpuId", ListSort.by(CpuDeviceListItemResponse::socket)),
                    Map.entry("socket", ListSort.by(CpuDeviceListItemResponse::socket)),
                    Map.entry("vendor", ListSort.byNullsAsLargest(CpuDeviceListItemResponse::vendor, String.CASE_INSENSITIVE_ORDER)),
                    Map.entry("model", ListSort.byNullsAsLargest(CpuDeviceListItemResponse::model, String.CASE_INSENSITIVE_ORDER)),
                    Map.entry("coreCount", ListSort.by(CpuDeviceListItemResponse::coreCount)),
                    Map.entry("threadCount", ListSort.by(CpuDeviceListItemResponse::threadCount)),
                    Map.entry("baseClockGhz", ListSort.byNullsAsLargest(CpuDeviceListItemResponse::baseClockGhz))),
            Comparator.comparingInt(CpuDeviceListItemResponse::socket));

    /** node_cpu_info 의 model_name 표기 "Intel(R) Xeon(R) Gold 6338 CPU @ 2.00GHz" 에서 기본 클럭을 뽑는다 */
    private static final Pattern BASE_CLOCK = Pattern.compile("@\\s*(\\d+(?:\\.\\d+)?)\\s*GHz", Pattern.CASE_INSENSITIVE);

    private final NodeAccessService nodeAccess;
    private final HostCategoryMetricsQueryService metrics;

    public ResourceExplorerNodeCpuService(NodeAccessService nodeAccess, HostCategoryMetricsQueryService metrics) {
        this.nodeAccess = nodeAccess;
        this.metrics = metrics;
    }

    // ── 공개 API ────────────────────────────────────────────────

    /** 개요 탭 KPI 카드 4종 — Used / I/O Wait / System / Steal (%, 최근 5분 rate). 미수집은 null */
    public CpuSummaryResponse getCpuSummary(String nodeId) {
        MetricSelector selector = nodeAccess.selector(nodeId);
        if (selector == null) {
            return new CpuSummaryResponse(null, null, null, null);
        }
        return metrics.cpuSummary(selector);
    }

    /**
     * 개요 탭 CPU 목록 — CPU ID/소켓/벤더/모델/코어·스레드/기본 클럭/수집 상태/마지막 수집, 페이징(1부터)·컬럼 정렬.
     * 수집 상태는 목록을 만들 표본이 Thanos 에 있으면 COLLECTING 이고, 없으면 목록 자체가 빈다.
     */
    public PageResponse<CpuDeviceListItemResponse> getCpuDevices(String nodeId, int page, int size, String sort) {
        Comparator<CpuDeviceListItemResponse> comparator = SORT.comparator(sort);   // 미지원 필드는 조회 전에 400
        MetricSelector selector = nodeAccess.selector(nodeId);
        if (selector == null) {
            return PageResponse.of(List.of(), page, size);
        }

        CpuInventory inventory = metrics.cpuInventory(selector);
        OffsetDateTime collectedAt = MetricFormat.toOffsetDateTime(inventory.collectedAt());
        List<CpuDeviceListItemResponse> rows = inventory.devices().stream()
                .map(device -> row(device, collectedAt))
                .sorted(comparator)
                .toList();
        return PageResponse.of(rows, page, size);
    }

    /** 모니터링 탭 차트 — CPU mode 별 시계열. 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep */
    public CpuMetricTrendResponse getCpuMetricTrend(String nodeId, CpuMetric metric, NodeMetricInterval interval) {
        MetricSelector selector = nodeAccess.selector(nodeId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<DataPoint> points = selector == null ? List.of() : metrics.cpuTrend(metric, selector, from, to, step);
        return new CpuMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("cpu", points)));
    }

    // ── 조회 대상 ───────────────────────────────────────────────

    // ── 행 구성 ─────────────────────────────────────────────────

    static CpuDeviceListItemResponse row(CpuDevice device, OffsetDateTime collectedAt) {
        return new CpuDeviceListItemResponse(
                "cpu-" + device.socket(),
                device.socket(),
                device.vendor(),
                device.model(),
                device.coreCount(),
                device.threadCount(),
                baseClockGhz(device.model()),
                MonitoringStatus.COLLECTING.name(),
                collectedAt);
    }

    /** model_name 에 "@ 2.00GHz" 가 있으면 그 값, 없으면(AMD EPYC 등) null */
    static Double baseClockGhz(String model) {
        if (model == null) {
            return null;
        }
        Matcher matcher = BASE_CLOCK.matcher(model);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
    }

}

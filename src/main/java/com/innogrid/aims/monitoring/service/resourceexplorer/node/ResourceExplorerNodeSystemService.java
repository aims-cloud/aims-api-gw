package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService.SystemInfo;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService.SystemSummary;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 System 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 System 항목 선택 시 개요(KPI 카드·시스템 정보 표)와 모니터링(지표 5종 차트) 탭 데이터를 제공한다.
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드 node_exporter 의 loadavg·stat·boottime·uname·os 지표를 {@code instance} 라벨로 좁혀 읽는다.
 * node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 미수집(null·NOT_CONFIGURED·빈 시리즈)으로 내린다.
 */
@Service
public class ResourceExplorerNodeSystemService {

    private final NodeAccessService nodeAccess;
    private final HostSystemMetricsQueryService metrics;

    public ResourceExplorerNodeSystemService(NodeAccessService nodeAccess, HostSystemMetricsQueryService metrics) {
        this.nodeAccess = nodeAccess;
        this.metrics = metrics;
    }

    /** 개요 탭 KPI 카드 — 가동 시간(초), 부하 1/5/15, 코어 수, 컨텍스트 스위치·인터럽트 /s(최근 5분). 미수집은 null */
    public SystemSummaryResponse getSystemSummary(String nodeId) {
        MetricSelector selector = selector(nodeId);
        SystemSummary summary = selector == null ? SystemSummary.EMPTY : metrics.summary(selector);
        return new SystemSummaryResponse(
                uptimeSeconds(summary.bootTimeSeconds()),
                summary.load1(), summary.load5(), summary.load15(),
                summary.coreCount(),
                summary.contextSwitchesPerSec(), summary.interruptsPerSec());
    }

    /** 개요 탭 시스템 정보 표 — 호스트명·OS·커널·아키텍처·부팅 시각. exporter 없으면 NOT_CONFIGURED, 표본 없으면 UNAVAILABLE */
    public SystemInfoResponse getSystemInfo(String nodeId) {
        MetricSelector selector = selector(nodeId);
        if (selector == null) {
            return new SystemInfoResponse(null, null, null, null, null, null, MonitoringStatus.NOT_CONFIGURED.name(), null);
        }
        Optional<SystemInfo> info = metrics.info(selector);
        if (info.isEmpty()) {
            return new SystemInfoResponse(null, null, null, null, null, null, MonitoringStatus.UNAVAILABLE.name(), null);
        }
        SystemInfo i = info.get();
        return new SystemInfoResponse(i.hostname(), i.osName(), i.osVersion(), i.kernelRelease(), i.architecture(),
                i.bootTimeSeconds() == null ? null : toOffsetDateTime(Instant.ofEpochSecond(i.bootTimeSeconds())),
                MonitoringStatus.COLLECTING.name(),
                toOffsetDateTime(i.collectedAt()));
    }

    /** 모니터링 탭 차트 — 지표별 시계열. 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep */
    public SystemMetricTrendResponse getSystemMetricTrend(String nodeId, SystemMetric metric, NodeMetricInterval interval) {
        MetricSelector selector = selector(nodeId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<DataPoint> points = selector == null ? List.of() : metrics.trend(metric, selector, from, to, step);
        return new SystemMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("system", points)));
    }

    /** 스코프 검증(404) 뒤 node_exporter 선택자. exporter 타겟이 없으면 null — 호출 측은 조회 없이 미수집으로 처리한다 */
    private MetricSelector selector(String nodeId) {
        String instance = nodeAccess.require(nodeId).host().instance();
        return instance == null ? null : MetricSelector.instance(instance);
    }

    static Long uptimeSeconds(Long bootTimeSeconds) {
        if (bootTimeSeconds == null) {
            return null;
        }
        return Math.max(0L, Instant.now().getEpochSecond() - bootTimeSeconds);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}

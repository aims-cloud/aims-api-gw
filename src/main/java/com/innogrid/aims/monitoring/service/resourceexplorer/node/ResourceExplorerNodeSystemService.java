package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.util.List;

import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSystemMetricsQueryService.SystemSummary;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.SystemPanelAssembler;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 System 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 System 항목 선택 시 개요(KPI 카드·시스템 정보 표)와 모니터링(지표 5종 차트) 탭 데이터를 제공한다.
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드 node_exporter 의 loadavg·stat·boottime·uname·os 지표를 {@code instance} 라벨로 좁혀 읽는다.
 * node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 미수집(null·NOT_CONFIGURED·빈 시리즈)으로 내린다.
 * 응답 조립 규칙은 VM System 패널과 공유한다({@link SystemPanelAssembler}).
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
        MetricSelector selector = nodeAccess.selector(nodeId);
        return SystemPanelAssembler.summary(selector == null ? SystemSummary.EMPTY : metrics.summary(selector));
    }

    /** 개요 탭 시스템 정보 표 — 호스트명·OS·커널·아키텍처·부팅 시각. exporter 없으면 NOT_CONFIGURED, 표본 없으면 UNAVAILABLE */
    public SystemInfoResponse getSystemInfo(String nodeId) {
        MetricSelector selector = nodeAccess.selector(nodeId);
        if (selector == null) {
            return SystemPanelAssembler.emptyInfo(MonitoringStatus.NOT_CONFIGURED);
        }
        return SystemPanelAssembler.info(metrics.info(selector));
    }

    /** 모니터링 탭 차트 — 지표별 시계열. 미수집이면 빈 시리즈 */
    public SystemMetricTrendResponse getSystemMetricTrend(String nodeId, SystemMetric metric, NodeMetricInterval interval) {
        MetricSelector selector = nodeAccess.selector(nodeId);
        return SystemPanelAssembler.trend(metric, interval, (from, to, step) ->
                selector == null ? List.of() : metrics.trend(metric, selector, from, to, step));
    }
}

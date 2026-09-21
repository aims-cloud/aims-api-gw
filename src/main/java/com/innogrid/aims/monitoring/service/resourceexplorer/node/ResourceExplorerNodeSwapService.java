package com.innogrid.aims.monitoring.service.resourceexplorer.node;

import java.time.Instant;
import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse.DataPoint;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapMetricTrendResponse.Series;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.HostSwapMetricsQueryService;
import com.innogrid.aims.monitoring.service.resourceexplorer.tree.MetricSelector;
import org.springframework.stereotype.Service;

/**
 * 자원 탐색기 노드 Swap 카테고리 패널 조회 서비스.
 * 트리에서 노드 하위 Swap 항목 선택 시 개요(KPI 카드 6종)와 모니터링(지표 6종 차트) 탭 데이터를 제공한다.
 *
 * nodeId 는 assets.id 이며 역할 스코프 밖이면 404({@link NodeAccessService}).
 * 지표는 노드 node_exporter 의 node_memory_Swap*·node_vmstat_pswp* 를 {@code instance} 라벨로 좁혀 읽는다.
 * node_exporter 타겟이 없는 노드는 Thanos 를 조회하지 않고 미수집(null·빈 시리즈)으로 내린다.
 * 스왑이 없는 호스트는 트리에서 NO_DATA 로 표시되지만 패널을 열면 용량 0·사용률 null 로 응답한다.
 */
@Service
public class ResourceExplorerNodeSwapService {

    private final NodeAccessService nodeAccess;
    private final HostSwapMetricsQueryService metrics;

    public ResourceExplorerNodeSwapService(NodeAccessService nodeAccess, HostSwapMetricsQueryService metrics) {
        this.nodeAccess = nodeAccess;
        this.metrics = metrics;
    }

    /** 개요 탭 KPI 카드 6종 — Total / Used / Free (GB), 사용률(%), 스왑 인·아웃(pages/s, 최근 5분). 미수집은 null */
    public SwapSummaryResponse getSwapSummary(String nodeId) {
        MetricSelector selector = selector(nodeId);
        if (selector == null) {
            return new SwapSummaryResponse(null, null, null, null, null, null);
        }
        return metrics.summary(selector);
    }

    /** 모니터링 탭 차트 — 지표별 시계열. 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep */
    public SwapMetricTrendResponse getSwapMetricTrend(String nodeId, SwapMetric metric, NodeMetricInterval interval) {
        MetricSelector selector = selector(nodeId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<DataPoint> points = selector == null ? List.of() : metrics.trend(metric, selector, from, to, step);
        return new SwapMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("swap", points)));
    }

    /** 스코프 검증(404) 뒤 node_exporter 선택자. exporter 타겟이 없으면 null — 호출 측은 조회 없이 미수집으로 처리한다 */
    private MetricSelector selector(String nodeId) {
        String instance = nodeAccess.require(nodeId).host().instance();
        return instance == null ? null : MetricSelector.instance(instance);
    }
}

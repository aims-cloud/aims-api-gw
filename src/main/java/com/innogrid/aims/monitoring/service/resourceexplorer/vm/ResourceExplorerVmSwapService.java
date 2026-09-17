package com.innogrid.aims.monitoring.service.resourceexplorer.vm;

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
 * 자원 탐색기 VM Swap 카테고리 패널 조회 서비스.
 * 트리에서 VM 하위 Swap 항목 선택 시 개요(KPI 카드 6종)와 모니터링(지표 6종 차트) 탭 데이터를 제공한다.
 *
 * VM 은 nsId·infraId·vmId 로 식별하고(스코프 밖·부재는 404), 지표는 VM 안 node_exporter 의
 * node_memory_Swap*·node_vmstat_pswp* 를 {@code tb_ns/tb_infra/tb_node} 라벨로 좁혀 읽는다.
 * VM 은 exporter 원장이 없어 항상 Thanos 를 조회하고 표본이 없으면 미수집(null·빈 시리즈)이다.
 * 클라우드 VM 은 스왑이 없는 경우가 많다 — 트리에서 NO_DATA 로 표시되고 패널은 용량 0·사용률 null 로 응답한다.
 * 응답 DTO 는 노드 Swap 패널과 같다.
 */
@Service
public class ResourceExplorerVmSwapService {

    private final TumblebugPanelSupport support;
    private final HostSwapMetricsQueryService metrics;

    public ResourceExplorerVmSwapService(TumblebugPanelSupport support, HostSwapMetricsQueryService metrics) {
        this.support = support;
        this.metrics = metrics;
    }

    /** 개요 탭 KPI 카드 6종 — Total / Used / Free (GB), 사용률(%), 스왑 인·아웃(pages/s, 최근 5분). 미수집은 null */
    public SwapSummaryResponse getSwapSummary(String roleName, String nsId, String infraId, String vmId) {
        return metrics.summary(selector(roleName, nsId, infraId, vmId));
    }

    /** 모니터링 탭 차트 — 지표별 시계열. 조회 구간은 지금 기준 interval 기간, 버킷 간격은 기간별 defaultStep */
    public SwapMetricTrendResponse getSwapMetricTrend(String roleName, String nsId, String infraId, String vmId,
                                                      SwapMetric metric, NodeMetricInterval interval) {
        MetricSelector sel = selector(roleName, nsId, infraId, vmId);
        Instant to = Instant.now();
        Instant from = to.minus(interval.window());
        String step = interval.defaultStep();

        List<DataPoint> points = metrics.trend(metric, sel, from, to, step);
        return new SwapMetricTrendResponse(metric, metric.unit(), from.getEpochSecond(), to.getEpochSecond(), step,
                points.isEmpty() ? List.of() : List.of(new Series("swap", points)));
    }

    /** 스코프·존재 검증(404) 뒤 tb_* 라벨 선택자 */
    private MetricSelector selector(String roleName, String nsId, String infraId, String vmId) {
        support.accessibleNode(roleName, nsId, infraId, vmId);
        return MetricSelector.tumblebugNode(nsId, infraId, vmId);
    }
}

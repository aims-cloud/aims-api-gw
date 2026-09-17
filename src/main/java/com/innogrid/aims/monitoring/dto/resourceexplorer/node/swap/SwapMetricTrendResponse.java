package com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap;

import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse;

/** Swap 카테고리 모니터링 탭 차트 1개의 시계열. */
public record SwapMetricTrendResponse(
        SwapMetric metric,
        String unit,
        long from,
        long to,
        String step,
        List<Series> series
) {
    /** 시계열 시리즈 — 노드 전체 1개 */
    public record Series(String name, List<MetricTrendResponse.DataPoint> dataPoints) {}
}

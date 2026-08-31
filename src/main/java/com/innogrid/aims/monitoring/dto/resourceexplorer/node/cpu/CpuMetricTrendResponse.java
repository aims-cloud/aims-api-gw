package com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu;

import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse;

/** CPU 카테고리 모니터링 탭 차트 1개의 시계열. */
public record CpuMetricTrendResponse(
        CpuMetric metric,
        String unit,
        long from,
        long to,
        String step,
        List<Series> series
) {
    /** 시계열 시리즈 — 기본은 노드 전체 1개, 필요 시 CPU 별로 분해한다 */
    public record Series(String name, List<MetricTrendResponse.DataPoint> dataPoints) {}
}

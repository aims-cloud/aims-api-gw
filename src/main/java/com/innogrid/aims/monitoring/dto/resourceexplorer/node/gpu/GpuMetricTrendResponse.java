package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse;

/** GPU 카테고리 모니터링 탭 차트 1개의 시계열. */
public record GpuMetricTrendResponse(
        GpuMetric metric,
        String unit,
        long from,
        long to,
        String step,
        List<Series> series
) {
    /** 시계열 시리즈 — 그룹은 GPU 별 분해, 개별 GPU 는 1개 */
    public record Series(String name, List<MetricTrendResponse.DataPoint> dataPoints) {}
}

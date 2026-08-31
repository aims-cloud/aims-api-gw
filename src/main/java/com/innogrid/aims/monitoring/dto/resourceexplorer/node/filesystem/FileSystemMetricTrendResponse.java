package com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem;

import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse;

/** File system 카테고리 모니터링 탭 차트 1개의 시계열. */
public record FileSystemMetricTrendResponse(
        FileSystemMetric metric,
        String unit,
        long from,
        long to,
        String step,
        List<Series> series
) {
    /** 시계열 시리즈 — 그룹은 마운트 전체 합산 1개, 개별 마운트는 해당 마운트 1개 */
    public record Series(String name, List<MetricTrendResponse.DataPoint> dataPoints) {}
}

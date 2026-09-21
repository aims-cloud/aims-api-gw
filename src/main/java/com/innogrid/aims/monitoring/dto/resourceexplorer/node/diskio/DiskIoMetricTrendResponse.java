package com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio;

import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse;

/** Disk I/O 카테고리 모니터링 탭 차트 1개의 시계열. */
public record DiskIoMetricTrendResponse(
        DiskIoMetric metric,
        String unit,
        String device,              // 특정 장치 조회면 그 장치, 전체 합산이면 null
        long from,
        long to,
        String step,
        List<Series> series
) {
    /** 시계열 시리즈 — 전체 합산은 "disk_io" 1개, 장치 지정 시 장치명 1개 */
    public record Series(String name, List<MetricTrendResponse.DataPoint> dataPoints) {}
}

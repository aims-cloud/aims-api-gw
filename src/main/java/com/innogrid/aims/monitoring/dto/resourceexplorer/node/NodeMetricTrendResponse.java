package com.innogrid.aims.monitoring.dto.resourceexplorer.node;

import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse;

/**
 * 노드 상세 모니터링 요약 차트 1개의 시계열.
 * NETWORK 는 수신/송신 2개 시리즈, 나머지 지표는 1개 시리즈를 담는다.
 */
public record NodeMetricTrendResponse(
        NodeDetailMetric metric,
        String unit,
        long from,
        long to,
        String step,
        List<Series> series
) {
    /** 시계열 시리즈 — NETWORK 의 name 은 receive/transmit */
    public record Series(String name, List<MetricTrendResponse.DataPoint> dataPoints) {}
}


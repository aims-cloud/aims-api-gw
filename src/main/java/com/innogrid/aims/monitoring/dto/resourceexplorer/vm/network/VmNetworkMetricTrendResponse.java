package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.network;

import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse;

/** VM Network 카테고리 모니터링 탭 차트 1개의 시계열. */
public record VmNetworkMetricTrendResponse(
        VmNetworkMetric metric,
        String unit,
        long from,
        long to,
        String step,
        List<Series> series
) {
    /** 시계열 시리즈 — 기본은 VM NIC 합산 1개, 필요 시 인터페이스별로 분해한다 */
    public record Series(String name, List<MetricTrendResponse.DataPoint> dataPoints) {}
}

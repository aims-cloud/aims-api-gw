package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

import java.util.List;

import com.innogrid.aims.monitoring.dto.resource.MetricTrendResponse;

/**
 * PCIe Replay Counter 상세 패널 — GPU별 현황 표와 최근 24시간 증가 추이(회/h)를 한 응답에 담는다.
 * (패널이 단일 화면이라 현황·추이를 분리 호출하지 않는다)
 */
public record GpuPcieReplayResponse(
        List<GpuCounter> counters,
        List<TrendSeries> trend24h
) {
    /** GPU 1장의 현황 — 누적값 / 최근 1시간 증가 / 최근 24시간 증가. 수집되지 않으면 null. gpuName 은 트리와 같은 표시 이름 */
    public record GpuCounter(String gpuId, String gpuName, Long cumulative, Long delta1h, Long delta24h) {}

    /** 증가 추이 시리즈 — 값 단위: 회/h (1시간 간격 increase) */
    public record TrendSeries(String gpuId, String gpuName, List<MetricTrendResponse.DataPoint> dataPoints) {}

    public GpuPcieReplayResponse {
        counters = counters == null ? List.of() : List.copyOf(counters);
        trend24h = trend24h == null ? List.of() : List.copyOf(trend24h);
    }
}

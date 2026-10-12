package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

import java.time.OffsetDateTime;
import java.util.List;

/** 수집 상태 상세 패널 (개별 GPU) — 요약 표와 수집 지표 상세 체크리스트. */
public record GpuDeviceCollectStatusResponse(
        String gpuId,
        String gpuName,
        String model,
        GpuCollectState state,
        OffsetDateTime lastCollectedAt,
        int collectIntervalSeconds,
        int collectedMetricCount,
        int totalMetricCount,
        List<MetricCollectStatus> metrics
) {
    /** 지표 1개의 지금 수집 여부 — 분모는 dcgm_exporter 기본 수집 지표 7종(HostGpuMetricsQueryService.EXPECTED_METRICS) */
    public record MetricCollectStatus(String metricName, boolean collected) {}

    public GpuDeviceCollectStatusResponse {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }
}

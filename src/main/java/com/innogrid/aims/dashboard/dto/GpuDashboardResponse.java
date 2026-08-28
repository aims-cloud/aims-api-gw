package com.innogrid.aims.dashboard.dto;

import java.util.List;

public record GpuDashboardResponse(
        GpuSummaryResponse summary,
        List<GpuDistributionResponse> vmDistribution,
        List<GpuDistributionResponse> gpuTypeDistribution,
        List<GpuRankingResponse> vmGpuUtilizationTop,
        List<GpuRankingResponse> vmVramUtilizationTop,
        List<GpuRankingResponse> gpuUtilizationTop,
        List<GpuRankingResponse> gpuVramUtilizationTop,
        GpuThresholdResponse thresholds,
        String dataSource) {
}

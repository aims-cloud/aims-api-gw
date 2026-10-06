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
        String dataSource,
        GpuNodeCoverageResponse nodeCoverage) {   // 2026-10-06 추가 — GPU 노드 수집 현황(미등록 수는 ADMIN 만)
}

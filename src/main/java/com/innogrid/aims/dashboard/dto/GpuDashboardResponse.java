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
        GpuNodeCoverageResponse nodeCoverage,             // 2026-10-06 — GPU 노드 수집 현황(미등록 수는 ADMIN 만)
        List<GpuUnitPointResponse> gpuDistribution,       // 2026-10-06 — GPU 할당 단위 점(R-13)
        List<GpuRankingResponse> gpuVramUsedTop,          // 2026-10-06 — GPU Top5 VRAM Used(R-15)
        List<GpuRankingResponse> gpuPeakVramTop) {        // 2026-10-06 — GPU Top5 Peak VRAM(R-15)
}

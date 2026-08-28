package com.innogrid.aims.dashboard.dto;

public record GpuSummaryResponse(
        long allocatedGpuCount,
        long idleGpuCount,
        long highUtilizationGpuCount,
        double vramUtilization,
        double vramUsedMb,
        double vramTotalMb,
        double peakVramMb) {
}

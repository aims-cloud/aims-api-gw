package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

/** GPU 그룹 개요 탭의 KPI 카드 4종. */
public record GpuGroupSummaryResponse(
        int totalGpuCount,
        double vramUtilizationPercent,
        double vramUsedGb,
        double vramTotalGb,
        double totalPowerWatts,
        double peakPowerWatts,
        double maxTemperatureCelsius
) {
}

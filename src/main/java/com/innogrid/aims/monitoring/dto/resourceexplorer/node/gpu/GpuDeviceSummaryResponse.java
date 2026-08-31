package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

/** 개별 GPU 개요 탭의 KPI 카드 6종. */
public record GpuDeviceSummaryResponse(
        double gpuUtilizationPercent,
        double vramUtilizationPercent,
        double vramUsedGb,
        double vramTotalGb,
        double powerWatts,
        double temperatureCelsius,
        double fanSpeedPercent
) {
}

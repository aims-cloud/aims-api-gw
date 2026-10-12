package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

/**
 * GPU 그룹 개요 탭의 KPI 카드 4종.
 * totalGpuCount 는 최근 24시간 안에 수집된 GPU 수, 나머지는 지금 수집 중인 GPU 기준이며 없으면 null(미수집).
 * peakPowerWatts 는 GPU 1장의 현재 전력 최댓값이다.
 */
public record GpuGroupSummaryResponse(
        int totalGpuCount,
        Double vramUtilizationPercent,
        Double vramUsedGb,
        Double vramTotalGb,
        Double totalPowerWatts,
        Double peakPowerWatts,
        Double maxTemperatureCelsius
) {
}

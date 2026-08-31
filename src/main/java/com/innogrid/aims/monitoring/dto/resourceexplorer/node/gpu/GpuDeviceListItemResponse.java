package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

/**
 * GPU 그룹 개요 탭의 GPU 목록 1행.
 * Util 컬럼은 "현재/평균" 표기를 위해 현재값과 조회 기간 평균을 함께 담는다.
 */
public record GpuDeviceListItemResponse(
        String gpuId,
        String model,
        double gpuUtilCurrentPercent,
        double gpuUtilAvgPercent,
        double vramUtilCurrentPercent,
        double vramUtilAvgPercent,
        double vramUsedGb,
        double vramTotalGb,
        double temperatureCelsius,
        double powerWatts,
        int recentAlarmCount              // AlarmHistory 연계 — 집계 기간 TODO:: 확정 필요
) {
}

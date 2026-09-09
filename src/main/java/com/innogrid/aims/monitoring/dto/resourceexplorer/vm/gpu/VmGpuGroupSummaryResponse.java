package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu;

/** VM GPU 그룹 개요 탭의 KPI 카드 6종. */
public record VmGpuGroupSummaryResponse(
        int totalGpuCount,
        double maxGpuUtilizationPercent,
        double avgGpuUtilizationPercent,
        double avgGpuMemoryUtilizationPercent,
        double maxGpuMemoryUtilizationPercent,
        long recentAlarmCount           // AlarmHistory 연계 — TODO:: 집계 기간 확정 후 작업 예정 (노드 GPU 목록과 공통, 현재 미구현·501)
) {
}

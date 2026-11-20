package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu;

/**
 * VM GPU 그룹 개요 탭의 KPI 카드 6종.
 * totalGpuCount 는 최근 24시간 안에 수집된 GPU 수, 사용률은 지금 수집 중인 GPU 기준이며 없으면 null(미수집).
 * GPU Memory Utilization 은 VRAM 사용률(사용/전체) — 노드 GPU 패널·GPU 대시보드와 같은 기준.
 */
public record VmGpuGroupSummaryResponse(
        int totalGpuCount,
        Double maxGpuUtilizationPercent,
        Double avgGpuUtilizationPercent,
        Double avgGpuMemoryUtilizationPercent,
        Double maxGpuMemoryUtilizationPercent,
        Long recentAlarmCount           // GPU 알람이 GPU 단위로 기록되지 않아 null — TODO:: 알람이 GPU(UUID) 단위로 기록되면 집계
) {
}

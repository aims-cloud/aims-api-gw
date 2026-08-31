package com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu;

/** CPU 카테고리 개요 탭의 KPI 카드 4종 (%). */
public record CpuSummaryResponse(
        double usedPercent,
        double ioWaitPercent,
        double systemPercent,
        double stealPercent
) {
}

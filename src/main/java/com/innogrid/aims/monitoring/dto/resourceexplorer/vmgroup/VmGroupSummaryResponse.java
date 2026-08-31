package com.innogrid.aims.monitoring.dto.resourceexplorer.vmgroup;

/** 가상머신 그룹 개요 탭의 KPI 카드 4종 — 가상머신 수 / Running / Stop / Error. */
public record VmGroupSummaryResponse(
        long totalCount,
        long runningCount,
        long stopCount,
        long errorCount
) {
}

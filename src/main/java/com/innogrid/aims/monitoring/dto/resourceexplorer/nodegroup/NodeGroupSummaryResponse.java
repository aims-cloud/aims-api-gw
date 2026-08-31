package com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup;

/** 노드 그룹 개요 탭의 KPI 카드 4종. */
public record NodeGroupSummaryResponse(
        long totalCount,
        long runningCount,
        long stopCount,
        long errorCount
) {
}


package com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup;

/** 노드 그룹 개요 탭의 KPI 카드 — 노드 수와 Running/Pending/Stop/Error 카운트. */
public record NodeGroupSummaryResponse(
        long totalCount,
        long runningCount,
        long pendingCount,
        long stopCount,
        long errorCount
) {}


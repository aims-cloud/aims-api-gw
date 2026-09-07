package com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup;

/**
 * Tumblebug NodeGroup(또는 미분류) 개요 탭의 KPI 카드 — VM 수와 Running/Pending/Stop/Error 카운트.
 * 상태 카운트는 Tumblebug 상태값을 docs/06 §6.1 버킷으로 묶은 값이다.
 */
public record NodeGroupSummaryResponse(
        long totalCount,
        long runningCount,
        long pendingCount,
        long stopCount,
        long errorCount
) {
}

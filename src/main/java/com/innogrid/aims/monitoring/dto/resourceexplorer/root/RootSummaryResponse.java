package com.innogrid.aims.monitoring.dto.resourceexplorer.root;

/**
 * 전체 자원 패널의 KPI 카드 8종 — 역할/배포영역/노드/가상머신 수와 상태별 카운트.
 * 상태 카운트는 노드+가상머신 합산 기준(도넛 두 개의 상태별 합과 일치).
 */
public record RootSummaryResponse(
        long roleCount,
        long deploymentCount,
        long nodeCount,
        long vmCount,
        long runningCount,
        long errorCount,
        long pendingCount,
        long stopCount
) {
}

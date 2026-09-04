package com.innogrid.aims.monitoring.dto.resourceexplorer.root;

/**
 * 전체 자원 패널의 KPI 카드 8종 — 역할/MDC/물리 호스트/가상머신 수와 상태별 카운트.
 * 집계 범위는 현재 사용자 토큰의 역할 스코프(트리와 동일)이며, 같은 자원이 여러 역할에 있으면 한 번만 센다.
 * 상태 카운트는 물리 호스트+가상머신 합산 기준(도넛 두 개의 상태별 합과 일치).
 */
public record RootSummaryResponse(
        long roleCount,
        long mdcCount,
        long nodeCount,
        long vmCount,
        long runningCount,
        long errorCount,
        long pendingCount,
        long stopCount
) {
}

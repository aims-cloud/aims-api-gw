package com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot;

/**
 * 역할 하위 "물리 노드" 그룹 루트(PHYSICAL_ROOT) 개요 KPI.
 * 상태 카운트는 물리 호스트 기준이며, 호스트 위 1세부 가상자원은 vmCount 로만 센다.
 */
public record PhysicalRootSummaryResponse(
        long mdcCount,
        long nodeCount,
        long vmCount,
        long runningCount,
        long pendingCount,
        long stopCount,
        long errorCount
) {
}

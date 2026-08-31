package com.innogrid.aims.monitoring.dto.resourceexplorer.role;

/**
 * 역할 패널의 KPI 카드 8종 — 멤버/배포영역/노드/가상머신 수와 상태별 카운트.
 * 상태 카운트(Running/Pending/Stop/Error)의 집계 대상(노드+VM 합산 여부)은 TODO:: 확정 필요.
 * Pending 상태는 이 화면에서 처음 등장 — 노드·VM 상태 값 체계(NodeStatus)와의 정합은 TODO:: 확정 필요.
 */
public record RoleSummaryResponse(
        long memberCount,
        long deploymentCount,
        long nodeCount,
        long vmCount,
        long runningCount,
        long pendingCount,
        long stopCount,
        long errorCount
) {
}

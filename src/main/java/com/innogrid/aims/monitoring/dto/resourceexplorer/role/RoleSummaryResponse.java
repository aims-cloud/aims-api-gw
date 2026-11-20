package com.innogrid.aims.monitoring.dto.resourceexplorer.role;

/**
 * 역할 패널의 KPI 카드 — 멤버/MDC/Namespace/물리 호스트/가상머신 수와 상태별 카운트.
 * 상태 카운트(Running/Pending/Stop/Error)는 물리 호스트+가상머신 합산이며 노드·VM 도넛의 상태별 합과 일치한다.
 * 집계 범위는 해당 역할에 매핑된 자원(role_assets·role_namespaces)이다.
 */
public record RoleSummaryResponse(
        long memberCount,
        long mdcCount,
        long namespaceCount,
        long nodeCount,
        long vmCount,
        long runningCount,
        long pendingCount,
        long stopCount,
        long errorCount
) {
}

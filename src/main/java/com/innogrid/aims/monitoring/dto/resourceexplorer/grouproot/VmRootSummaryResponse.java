package com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot;

/**
 * 역할 하위 "가상머신" 그룹 루트(VM_ROOT) 개요 KPI — 역할에 매핑된 Tumblebug Namespace 전체 집계.
 * {@code tumblebugUnavailable} 은 이번 갱신에서 Tumblebug 조회가 하나라도 실패해 이전 값 또는 빈 값이 섞여 있다는 뜻이다.
 */
public record VmRootSummaryResponse(
        long namespaceCount,
        long infraCount,
        long vmCount,
        long runningCount,
        long pendingCount,
        long stopCount,
        long errorCount,
        boolean tumblebugUnavailable
) {
}

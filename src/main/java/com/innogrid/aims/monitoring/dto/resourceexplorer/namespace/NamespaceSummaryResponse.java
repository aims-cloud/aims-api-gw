package com.innogrid.aims.monitoring.dto.resourceexplorer.namespace;

/**
 * Tumblebug Namespace 개요 탭의 KPI 카드 — Infra 수, VM 수, Running/Pending/Stop/Error 카운트.
 * 상태 카운트는 Namespace 안 모든 Infra 의 Node(VM) 상태를 docs/06 §6.1 버킷으로 묶은 값이다.
 * {@code tumblebugUnavailable} 이 true 면 이번 갱신에서 Tumblebug 조회에 실패해 이전 값(없으면 0)이 내려간 상태다.
 */
public record NamespaceSummaryResponse(
        String nsId,
        String name,
        long infraCount,
        long vmCount,
        long runningCount,
        long pendingCount,
        long stopCount,
        long errorCount,
        boolean tumblebugUnavailable
) {
}

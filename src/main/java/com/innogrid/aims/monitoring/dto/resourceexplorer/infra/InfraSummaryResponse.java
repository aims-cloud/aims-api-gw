package com.innogrid.aims.monitoring.dto.resourceexplorer.infra;

/**
 * Tumblebug Infra 개요 탭 — 상태 원문, VM 수와 Running/Pending/Stop/Error 카운트, NodeGroup 수.
 * <ul>
 *   <li>{@code tumblebugStatus}: Infra 집계 상태 원문(예: "Running:1 (R:1/1)") — §6.1 에 따라 상세는 원문 노출</li>
 *   <li>상태 카운트: Infra 의 Node(VM) 상태를 §6.1 버킷으로 묶은 값 (Tumblebug statusCount 와 같은 근거, 버킷 단위)</li>
 *   <li>{@code unclassifiedCount}: nodeGroupId 가 없는 VM 수 — 0 이면 트리에도 미분류 노드가 없다</li>
 * </ul>
 */
public record InfraSummaryResponse(
        String infraId,
        String name,
        String tumblebugStatus,
        long vmCount,
        long runningCount,
        long pendingCount,
        long stopCount,
        long errorCount,
        long nodeGroupCount,
        long unclassifiedCount
) {
}

package com.innogrid.aims.monitoring.dto.resourceexplorer.namespace;

import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusSummary;

/**
 * Namespace 패널의 Infra 목록 1행 — 이름/Tumblebug 상태 원문/VM 수/VM 상태 스택바.
 * {@code tumblebugStatus} 는 Infra 집계 상태 원문(예: "Running:1 (R:1/1)")이며,
 * 스택바는 Infra 안 Node(VM) 상태를 §6.1 버킷으로 묶은 값이다.
 */
public record InfraListItemResponse(
        String infraId,
        String name,
        String tumblebugStatus,
        long vmCount,
        ResourceStatusSummary vmStatus
) {
}

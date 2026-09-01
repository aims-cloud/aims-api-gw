package com.innogrid.aims.monitoring.dto.resourceexplorer.role;

/**
 * 역할 패널의 배포영역 테이블 1행 — 이름/노드 수/노드 상태/가상머신 수/가상머신 상태.
 * 상태 컬럼은 "61/23/0/2" 형태의 Running/Error/Pending/Stop 스택바 —
 * 자원이 없어도 "0/0/0/0"으로 표시하므로 전체 자원 패널과 달리 null 없이 항상 값이 내려간다.
 */
public record RoleDeploymentListItemResponse(
        String deploymentId,
        String name,
        long nodeCount,
        StatusSummary nodeStatus,
        long vmCount,
        StatusSummary vmStatus
) {
    /** 상태 스택바 — 총계와 Running/Error/Pending/Stop 카운트 */
    public record StatusSummary(
            long total,
            long running,
            long error,
            long pending,
            long stop) {}
}

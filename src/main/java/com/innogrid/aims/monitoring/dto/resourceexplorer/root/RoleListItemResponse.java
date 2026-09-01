package com.innogrid.aims.monitoring.dto.resourceexplorer.root;

/**
 * 전체 자원 패널의 역할 테이블 1행.
 * 노드/가상머신 상태 컬럼은 "6/4/-/0" 형태의 스택바 — 값 없음("-")은 null 로 내려간다.
 */
public record RoleListItemResponse(
        String roleId,
        String name,                    // 화면에서 역할 패널로 링크
        String managerName,             // 관리자
        long memberCount,
        long deploymentCount,
        long nodeCount,
        StatusSummary nodeStatus,
        long vmCount,
        StatusSummary vmStatus
) {
    /** 상태 스택바 — 총계와 Running/Error/Pending/Stop 카운트 (값 없음은 null → "-") */
    public record StatusSummary(
            long total,
            Long running,
            Long error,
            Long pending,
            Long stop) {}
}

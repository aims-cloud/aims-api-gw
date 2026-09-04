package com.innogrid.aims.monitoring.dto.resourceexplorer.role;

/**
 * 역할 패널의 자원 영역 테이블 1행 — 역할 하위의 MDC(물리) 또는 Tumblebug Namespace(가상머신).
 * 이름/노드 수/노드 상태/가상머신 수/가상머신 상태.
 * <ul>
 *   <li>MDC 행: 노드 = 해당 MDC 의 물리 호스트, 가상머신 = 그 호스트들에 배치된 1세부 가상자원</li>
 *   <li>NAMESPACE 행: 노드 없음(0), 가상머신 = Namespace 의 Tumblebug Node</li>
 * </ul>
 * 상태 컬럼은 "61/23/0/2" 형태의 Running/Error/Pending/Stop 스택바 —
 * 자원이 없어도 "0/0/0/0"으로 표시하므로 전체 자원 패널과 달리 null 없이 항상 값이 내려간다.
 */
public record RoleAreaListItemResponse(
        String areaId,                  // MDC: mdcId, NAMESPACE: TB:{nsId}
        AreaKind kind,
        String name,
        long nodeCount,
        StatusSummary nodeStatus,
        long vmCount,
        StatusSummary vmStatus
) {
    public enum AreaKind { MDC, NAMESPACE }

    /** 상태 스택바 — 총계와 Running/Error/Pending/Stop 카운트 */
    public record StatusSummary(
            long total,
            long running,
            long error,
            long pending,
            long stop) {}
}

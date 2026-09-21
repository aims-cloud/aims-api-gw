package com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot;

import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusSummary;

/**
 * 물리 노드 그룹 루트의 MDC 목록 행 — 트리 MDC 노드({@code mdcId} 필드)와 매칭한다.
 * vm 은 그 MDC 의 물리 호스트 위에 배치된 1세부 가상자원이다.
 */
public record PhysicalRootMdcListItemResponse(
        String mdcId,
        String name,
        long nodeCount,
        ResourceStatusSummary nodeStatus,
        long vmCount,
        ResourceStatusSummary vmStatus
) {
}

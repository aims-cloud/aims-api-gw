package com.innogrid.aims.monitoring.dto.resourceexplorer.infra;

import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusSummary;

/**
 * Infra 패널의 NodeGroup 목록 1행 — NodeGroup 또는 미분류(nodeGroupId 없는 VM 묶음).
 * 미분류 행은 {@code nodeGroupId="@unclassified"}, {@code unclassified=true} 이며 VM 이 있을 때만 마지막에 나온다.
 * NodeGroup 패널 조회 시 {@code nodeGroupId} 를 그대로 넘기면 된다.
 */
public record NodeGroupListItemResponse(
        String nodeGroupId,
        String name,
        boolean unclassified,
        long vmCount,
        ResourceStatusSummary vmStatus
) {
}

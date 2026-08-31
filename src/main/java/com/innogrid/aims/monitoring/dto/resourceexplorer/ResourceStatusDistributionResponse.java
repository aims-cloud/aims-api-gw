package com.innogrid.aims.monitoring.dto.resourceexplorer;

import java.util.List;

/**
 * 노드 상태 / 가상머신 상태 도넛 위젯 — 총계(도넛 중앙)와 상태별 카운트(범례).
 * 전체 자원 패널과 역할 패널이 공용으로 사용한다.
 */
public record ResourceStatusDistributionResponse(
        String resourceKind,            // NODE | VM
        long totalCount,                // 도넛 중앙 표기 (예: 37 하이퍼바이저, 550 가상머신)
        List<StatusCount> statusCounts
) {
    /** 상태 1개의 카운트 — Running/Error/Pending/Stop 순 */
    public record StatusCount(ResourceStatus status, long count) {}

    public ResourceStatusDistributionResponse {
        statusCounts = statusCounts == null ? List.of() : List.copyOf(statusCounts);
    }
}

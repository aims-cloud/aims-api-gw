package com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus;

/**
 * Service Status 카테고리 개요 탭의 KPI 카드 — systemd 유닛 전체·상태별 개수.
 * node_exporter 에 {@code --collector.systemd} 가 없는 노드는 표본이 없어 전부 null — 화면에는 "-" 로 표시한다.
 */
public record ServiceStatusSummaryResponse(
        Integer totalCount,
        Integer activeCount,
        Integer inactiveCount,
        Integer failedCount,
        Integer activatingCount,
        Integer deactivatingCount
) {
}

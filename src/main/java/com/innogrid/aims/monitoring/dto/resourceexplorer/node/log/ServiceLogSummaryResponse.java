package com.innogrid.aims.monitoring.dto.resourceexplorer.node.log;

/** 로그 서브탭의 KPI 카드 4종 — 수집 상태 / 총 로그 수 / Warning / Error. */
public record ServiceLogSummaryResponse(
        String collectStatus,           // TODO:: 수집 상태 값 체계 확정 후 enum 전환
        long totalCount,
        long warningCount,
        long errorCount
) {
}

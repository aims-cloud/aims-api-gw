package com.innogrid.aims.monitoring.dto.resourceexplorer.node.log;

/** 로그 서브탭의 KPI 카드 4종 — 수집 상태 / 총 로그 수 / Warning / Error. */
public record ServiceLogSummaryResponse(
        LogStatus collectStatus,
        long totalCount,
        long warningCount,
        long errorCount,
        long unknownCount
) {
}

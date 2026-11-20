package com.innogrid.aims.monitoring.dto.resourceexplorer.mdc;

/** MDC 패널 개요 탭의 KPI 카드 — 물리 호스트 수와 Running/Pending/Stop/Error 카운트. */
public record MdcSummaryResponse(
        long totalCount,
        long runningCount,
        long pendingCount,
        long stopCount,
        long errorCount
) {}

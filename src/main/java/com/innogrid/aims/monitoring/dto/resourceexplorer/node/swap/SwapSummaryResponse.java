package com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap;

/**
 * Swap 카테고리 개요 탭의 KPI 카드 6종.
 * 용량은 GB, 스왑 인·아웃은 최근 5분 rate(pages/s). 미수집이면 null.
 * 스왑이 없는 호스트는 total·used·free 가 0 이고 usedPercent 는 null(정의 불가) — 화면에는 "-" 로 표시한다.
 */
public record SwapSummaryResponse(
        Double totalGb,
        Double usedGb,
        Double freeGb,
        Double usedPercent,
        Double swapInPerSec,
        Double swapOutPerSec
) {
}

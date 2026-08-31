package com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace;

/** 트레이스 서브탭의 KPI 카드 4종 — 수집 상태 / 총 요청 수 / 요청률 / 평균 응답. */
public record ServiceTraceSummaryResponse(
        String collectStatus,           // TODO:: 수집 상태 값 체계 확정 후 enum 전환
        long totalRequestCount,
        double requestRatePerSec,
        double avgResponseMs
) {
}

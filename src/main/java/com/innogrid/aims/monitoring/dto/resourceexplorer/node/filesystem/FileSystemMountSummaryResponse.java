package com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem;

/**
 * 개별 마운트 개요 탭의 KPI 카드 6종.
 * growth24hGb 는 최근 24시간 사용량 증가분(감소 시 음수),
 * inodeUsagePercent 는 수집 불가 시 null — 화면에는 "-" 로 표시한다.
 */
public record FileSystemMountSummaryResponse(
        double totalGb,
        double usedPercent,
        double usedGb,
        double freeGb,
        double growth24hGb,
        Double inodeUsagePercent
) {
}

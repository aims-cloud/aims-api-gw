package com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem;

/**
 * 개별 마운트 개요 탭의 KPI 카드 6종.
 * growth24hGb 는 최근 24시간 사용량 증가분(감소 시 음수)이며 Thanos 에서 구간을 계산할 수 없으면 null,
 * inodeUsagePercent 는 파일시스템이 inode 를 보고하지 않으면 null — 화면에는 "-" 로 표시한다.
 */
public record FileSystemMountSummaryResponse(
        double totalGb,
        double usedPercent,
        double usedGb,
        double freeGb,
        Double growth24hGb,
        Double inodeUsagePercent
) {
}

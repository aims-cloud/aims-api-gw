package com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu;

/**
 * CPU 카테고리 개요 탭의 KPI 카드 4종 (%) — 노드·VM CPU 패널 공용.
 * 미수집 지표는 null — 화면에는 "-" 로 표시한다.
 */
public record CpuSummaryResponse(
        Double usedPercent,
        Double ioWaitPercent,
        Double systemPercent,
        Double stealPercent
) {
}

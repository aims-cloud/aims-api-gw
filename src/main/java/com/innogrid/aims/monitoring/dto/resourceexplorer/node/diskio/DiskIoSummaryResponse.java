package com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio;

/**
 * Disk I/O 카테고리 개요 탭의 KPI 카드 — 블록 장치 전체 합산(최근 5분 rate).
 * 지연은 장치별 지연을 IOPS 로 가중 평균한 값이고, 사용률은 장치 평균이다.
 * 미수집이면 값은 null, 장치 수는 0 — 화면에는 "-" 로 표시한다.
 */
public record DiskIoSummaryResponse(
        int deviceCount,
        Double readBytesPerSec,
        Double writeBytesPerSec,
        Double readIops,
        Double writeIops,
        Double readLatencyMs,
        Double writeLatencyMs,
        Double utilizationPercent
) {
}

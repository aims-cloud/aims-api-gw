package com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio;

/**
 * Disk I/O 카테고리 개요 탭의 장치 목록 1행 — node_exporter {@code device} 라벨(sda, nvme0n1 …) 단위, 최근 5분 rate.
 * 지연은 해당 구간에 I/O 가 없으면 계산할 수 없어 null — 화면에는 "-" 로 표시한다.
 */
public record DiskIoDeviceListItemResponse(
        String device,
        Double readBytesPerSec,
        Double writeBytesPerSec,
        Double readIops,
        Double writeIops,
        Double readLatencyMs,
        Double writeLatencyMs,
        Double utilizationPercent
) {
}

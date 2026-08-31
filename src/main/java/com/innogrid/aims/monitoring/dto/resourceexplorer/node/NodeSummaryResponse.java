package com.innogrid.aims.monitoring.dto.resourceexplorer.node;

import java.time.OffsetDateTime;

/**
 * 노드 상세 개요 탭의 주요 지표 카드 6종.
 * lastUpdatedAt 은 카드 우상단 "최근 업데이트" 표기에 사용한다.
 */
public record NodeSummaryResponse(
        OffsetDateTime lastUpdatedAt,
        MetricCard cpu,
        CapacityMetricCard memory,
        CapacityMetricCard fileSystem,
        NetworkTrafficCard networkTraffic,
        GpuCard gpu,
        CapacityMetricCard vram
) {
    /** 사용률만 표시하는 카드 (CPU) */
    public record MetricCard(double utilizationPercent) {}

    /** 사용률 + Free/Total 용량을 표시하는 카드 (Memory, File System, VRAM) */
    public record CapacityMetricCard(double utilizationPercent, double freeGb, double totalGb) {}

    /** 네트워크 수신/송신 처리량 — bytes/sec 원시값이며 단위 표기는 프론트가 변환한다 */
    public record NetworkTrafficCard(double receiveBytesPerSec, double transmitBytesPerSec) {}

    /** GPU 사용률 카드 — 보조 표기는 탑재 GPU 수 (VRAM 용량은 VRAM 카드가 담당) */
    public record GpuCard(double utilizationPercent, int gpuCount) {}
}


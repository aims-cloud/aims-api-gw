package com.innogrid.aims.monitoring.dto.resourceexplorer.node;

import java.time.OffsetDateTime;

/**
 * 노드 상세 개요 탭의 주요 지표 카드 6종.
 * lastUpdatedAt 은 카드 우상단 "최근 업데이트" 표기에 사용한다.
 * 카드가 null 이면 해당 지표 미수집(또는 GPU 미탑재) — 화면은 "미수집"으로 표시한다.
 */
public record NodeSummaryResponse(
        OffsetDateTime lastUpdatedAt,
        CpuCard cpu,
        CapacityMetricCard memory,
        CapacityMetricCard fileSystem,
        NetworkTrafficCard networkTraffic,
        GpuCard gpu,
        CapacityMetricCard vram
) {
    /** CPU 사용률 카드 — 보조 표기는 탑재 코어 수 (GPU 카드의 gpuCount 와 대칭) */
    public record CpuCard(double utilizationPercent, int coreCount) {}

    /** 사용률 + Free/Total 용량을 표시하는 카드 (Memory, File System, VRAM) */
    public record CapacityMetricCard(double utilizationPercent, double freeGb, double totalGb) {}

    /** 네트워크 수신/송신 처리량 — bytes/sec 원시값이며 단위 표기는 프론트가 변환한다 */
    public record NetworkTrafficCard(double receiveBytesPerSec, double transmitBytesPerSec) {}

    /** GPU 사용률 카드 — 보조 표기는 탑재 GPU 수 (VRAM 용량은 VRAM 카드가 담당) */
    public record GpuCard(double utilizationPercent, int gpuCount) {}
}


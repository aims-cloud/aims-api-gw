package com.innogrid.aims.monitoring.dto.resourceexplorer.vm;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeSummaryResponse;

/**
 * VM 상세 개요 탭의 주요 지표 카드 5종 — 노드 상세와 달리 VRAM 카드가 없고
 * GPU 카드가 VRAM Free/Total(GB) 을 함께 표기한다.
 * lastUpdatedAt 은 카드 우상단 "최근 업데이트" 표기에 사용한다.
 */
public record VmSummaryResponse(
        OffsetDateTime lastUpdatedAt,
        NodeSummaryResponse.CpuCard cpu,        // coreCount 는 VM 에서는 vCPU 수
        NodeSummaryResponse.CapacityMetricCard memory,
        NodeSummaryResponse.CapacityMetricCard fileSystem,
        NodeSummaryResponse.NetworkTrafficCard networkTraffic,
        GpuVramCard gpu
) {
    /** GPU 사용률 카드 — 보조 표기는 VRAM Free/Total (GB) */
    public record GpuVramCard(double utilizationPercent, double vramFreeGb, double vramTotalGb) {}
}

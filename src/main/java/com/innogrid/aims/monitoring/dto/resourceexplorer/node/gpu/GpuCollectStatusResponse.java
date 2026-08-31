package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

import java.time.OffsetDateTime;
import java.util.List;

/** 수집 상태 상세 패널 (그룹) — 요약 표와 GPU별 수집 상태 카드. */
public record GpuCollectStatusResponse(
        int targetGpuCount,
        int healthyGpuCount,
        int failedGpuCount,
        OffsetDateTime lastCollectedAt,
        int collectIntervalSeconds,
        List<GpuCollectStatus> gpus
) {
    /** GPU 1장의 수집 상태 카드 — NO_DATA 면 lastCollectedAt·지표 수는 null */
    public record GpuCollectStatus(String gpuId, String model, GpuCollectState state,
                                   OffsetDateTime lastCollectedAt,
                                   Integer collectedMetricCount, Integer totalMetricCount) {}

    public GpuCollectStatusResponse {
        gpus = gpus == null ? List.of() : List.copyOf(gpus);
    }
}

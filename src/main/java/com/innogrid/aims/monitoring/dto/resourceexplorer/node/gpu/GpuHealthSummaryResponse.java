package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

/**
 * GPU 상태 요약 위젯 — 그룹(전체 GPU 집계)과 개별 GPU 가 같은 구조를 쓴다.
 * anomalyGpuCount 는 이상 징후가 감지된 GPU 수 (개별 GPU 조회 시 0 또는 1),
 * anomalyMessage 는 배너 문구(정상이면 null).
 */
public record GpuHealthSummaryResponse(
        GpuHealthLevel anomalyLevel,
        int anomalyGpuCount,
        String anomalyMessage,
        HealthCard collectStatus,
        HealthCard eccDbeErrors,          // DCGM_FI_DEV_ECC_DBE_VOL_TOTAL
        HealthCard pcieReplayCounter,     // DCGM_FI_DEV_PCIE_REPLAY_COUNTER
        HealthCard clocksEventReasons     // DCGM_FI_DEV_CLOCKS_EVENT_REASONS (구 CLOCK_THROTTLE_REASONS)
) {
    /** 상태 카드 1개 — count 가 없는 카드(수집 상태 등)는 null */
    public record HealthCard(GpuHealthLevel level, Integer count) {}
}

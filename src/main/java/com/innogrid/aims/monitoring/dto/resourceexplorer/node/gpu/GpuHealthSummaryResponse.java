package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

/**
 * GPU 상태 요약 위젯 — 그룹(전체 GPU 집계)과 개별 GPU 가 같은 구조를 쓴다.
 * anomalyGpuCount 는 이상 징후가 감지된 GPU 수 (개별 GPU 조회 시 0 또는 1),
 * anomalyMessage 는 배너 문구(정상이면 null).
 *
 * 카드 판정 — GPU 1장 기준 단계를 구해 그룹은 가장 나쁜 단계를 쓴다.
 * <ul>
 *   <li>수집 상태: 기대 지표 7종 전부 NORMAL, 일부 빠짐 WARNING, 전부 끊김 CRITICAL. count 없음(null)</li>
 *   <li>ECC DBE: 1건 이상 CRITICAL. count = DBE 누적 건수 합</li>
 *   <li>PCIe Replay: 최근 1시간에 늘었으면 WARNING. count = 최근 1시간 증가 합</li>
 *   <li>Clock 이벤트: 하드웨어 보호 CRITICAL, 드라이버 제한 WARNING, 유휴·설정은 정상. count = 이벤트가 걸린 GPU 수</li>
 * </ul>
 * 지표가 수집되지 않는 카드는 level·count 가 모두 null 이다(ECC DBE·Clock 이벤트는 dcgm_exporter 기본 수집 항목이 아님).
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
    /** 상태 카드 1개 — count 가 없는 카드(수집 상태)는 null, 지표가 수집되지 않으면 level·count 모두 null */
    public record HealthCard(GpuHealthLevel level, Integer count) {}
}

package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

/**
 * 개별 GPU 개요 탭의 KPI 카드 6종. 지금 수집되지 않는 값은 null(미수집).
 * fanSpeedPercent 는 dcgm_exporter 기본 수집 항목이 아니라 수집 설정에 DCGM_FI_DEV_FAN_SPEED 가 있어야 채워진다.
 */
public record GpuDeviceSummaryResponse(
        Double gpuUtilizationPercent,
        Double vramUtilizationPercent,
        Double vramUsedGb,
        Double vramTotalGb,
        Double powerWatts,
        Double temperatureCelsius,
        Double fanSpeedPercent
) {
}

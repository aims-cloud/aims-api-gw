package com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu;

/**
 * GPU 카테고리 모니터링 탭의 차트 지표.
 * TODO:: 모니터링 탭 화면 설계 확정 시 지표 세트 조정 (개요 카드 기준 잠정 6종)
 */
public enum GpuMetric {
    GPU_UTILIZATION("%"),
    VRAM_UTILIZATION("%"),
    VRAM_USED("GB"),
    GPU_POWER("W"),
    GPU_TEMPERATURE("°C"),
    FAN_SPEED("%");

    private final String unit;

    GpuMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}

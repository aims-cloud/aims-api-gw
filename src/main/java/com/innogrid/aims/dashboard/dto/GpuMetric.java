package com.innogrid.aims.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "GPU 대시보드 지표")
public enum GpuMetric {
    GPU_UTILIZATION,
    VRAM_UTILIZATION,
    VRAM_USED,
    PEAK_VRAM
}

package com.innogrid.aims.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "GPU 실시간 상태 응답")
public record GpuStatusResponse(
        @Schema(description = "GPU 사용률 (%)", example = "65.0")
        double gpuUtilization,
        @Schema(description = "GPU 메모리 사용량 (MB)", example = "8192.0")
        double gpuMemoryUsedMB,
        @Schema(description = "GPU 메모리 여유량 (MB)", example = "8192.0")
        double gpuMemoryFreeMB,
        @Schema(description = "GPU 온도 (°C)", example = "72.0")
        double gpuTemperature,
        @Schema(description = "GPU 전력 소모 (W)", example = "250.0")
        double gpuPowerWatts
) {}

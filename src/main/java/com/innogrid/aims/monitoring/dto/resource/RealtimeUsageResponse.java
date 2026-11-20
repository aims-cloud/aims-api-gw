package com.innogrid.aims.monitoring.dto.resource;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "실시간 리소스 사용률 응답")
public record RealtimeUsageResponse(
        @Schema(description = "CPU 사용률 (%)", example = "72.5")
        double cpuUsage,
        @Schema(description = "메모리 사용률 (%)", example = "85.3")
        double memoryUsage,
        @Schema(description = "디스크 사용률 (%)", example = "45.2")
        double diskUsage,
        @Schema(description = "네트워크 수신 속도 (bytes/sec)", example = "1048576.0")
        double networkRxBps,
        @Schema(description = "네트워크 송신 속도 (bytes/sec)", example = "524288.0")
        double networkTxBps
) {}


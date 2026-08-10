package com.innogrid.aims.monitoring.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "노드 성능 상태 응답")
public record NodeStatusResponse(
        @Schema(description = "1분 로드 평균", example = "2.5")
        double load1,
        @Schema(description = "5분 로드 평균", example = "2.1")
        double load5,
        @Schema(description = "15분 로드 평균", example = "1.8")
        double load15,
        @Schema(description = "실행 중 프로세스 수", example = "42")
        int procsRunning,
        @Schema(description = "CPU 사용률 (%)", example = "72.5")
        double cpuUsage,
        @Schema(description = "메모리 사용률 (%)", example = "85.3")
        double memoryUsage,
        @Schema(description = "디스크 사용률 (%)", example = "45.2")
        double diskUsage
) {}

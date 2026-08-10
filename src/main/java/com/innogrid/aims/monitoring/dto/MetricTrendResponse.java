package com.innogrid.aims.monitoring.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "시계열 추이 데이터 응답")
public record MetricTrendResponse(
        @Schema(description = "메트릭 이름", example = "cpu_usage")
        String metricName,
        @Schema(description = "인스턴스 (노드 주소)", example = "192.168.1.10:9100")
        String instance,
        @Schema(description = "데이터 포인트 목록")
        List<DataPoint> dataPoints
) {
    @Schema(description = "시계열 데이터 포인트")
    public record DataPoint(
            @Schema(description = "Unix 타임스탬프 (초)", example = "1723276800")
            long timestamp,
            @Schema(description = "측정값", example = "72.5")
            double value
    ) {}
}

package com.innogrid.aims.monitoring.dto.physical;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "물리 서버 IPMI 상태 응답")
public record PhysicalServerResponse(
        @Schema(description = "서버 식별자", example = "192.168.1.10")
        String serverId,
        @Schema(description = "전력 소모 (W)", example = "350.0")
        double powerWatts,
        @Schema(description = "온도 센서 목록")
        List<SensorReading> temperatures,
        @Schema(description = "팬 속도 센서 목록")
        List<SensorReading> fanSpeeds
) {
    @Schema(description = "센서 측정값")
    public record SensorReading(
            @Schema(description = "센서 이름", example = "CPU Temp")
            String name,
            @Schema(description = "측정값", example = "65.0")
            double value,
            @Schema(description = "단위", example = "°C")
            String unit
    ) {}
}


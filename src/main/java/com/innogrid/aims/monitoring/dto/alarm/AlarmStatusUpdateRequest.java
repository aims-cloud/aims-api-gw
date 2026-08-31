package com.innogrid.aims.monitoring.dto.alarm;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "알람 처리 상태 변경 요청")
public record AlarmStatusUpdateRequest(
        @Schema(description = "변경할 상태", example = "ACKNOWLEDGED",
                allowableValues = {"OPEN", "ACKNOWLEDGED", "RESOLVED"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String status
) {}

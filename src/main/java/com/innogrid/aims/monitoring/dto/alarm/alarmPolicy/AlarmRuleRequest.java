package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

import io.swagger.v3.oas.annotations.media.Schema;

@Deprecated
@Schema(description = "알람 규칙 생성/수정 요청")
public record AlarmRuleRequest(
        @Schema(description = "규칙 이름", example = "CPU 사용률 90% 초과", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "감시 메트릭", example = "cpu_usage", requiredMode = Schema.RequiredMode.REQUIRED)
        String metricName,
        @Schema(description = "조건 (gt, lt, eq)", example = "gt", allowableValues = {"gt", "lt", "eq", "gte", "lte"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String condition,
        @Schema(description = "임계값", example = "90.0", requiredMode = Schema.RequiredMode.REQUIRED)
        double threshold,
        @Schema(description = "심각도", example = "WARNING", allowableValues = {"INFO", "WARNING", "CRITICAL"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String severity,
        @Schema(description = "알림 채널", example = "email", allowableValues = {"email", "slack", "webhook"})
        String notifyChannel
) {}

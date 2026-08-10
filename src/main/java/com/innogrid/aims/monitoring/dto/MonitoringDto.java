package com.innogrid.aims.monitoring.dto;

import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import io.swagger.v3.oas.annotations.media.Schema;

public class MonitoringDto {

    @Schema(description = "수집 대상(에이전트) 등록 요청")
    public record RegisterAgentRequest(
            @Schema(description = "MDC 식별자", example = "mdc-01", requiredMode = Schema.RequiredMode.REQUIRED)
            String mdcId,
            @Schema(description = "호스트명", example = "node-1", requiredMode = Schema.RequiredMode.REQUIRED)
            String hostname,
            @Schema(description = "IP 주소", example = "192.168.1.10", requiredMode = Schema.RequiredMode.REQUIRED)
            String address,
            @Schema(description = "Exporter 포트", example = "9100", requiredMode = Schema.RequiredMode.REQUIRED)
            int port,
            @Schema(description = "Exporter 유형", example = "NODE_EXPORTER",
                    allowableValues = {"NODE_EXPORTER", "DCGM_EXPORTER", "IPMI_EXPORTER", "SNMP_EXPORTER"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            ExporterType exporterType
    ) {}

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

    @Schema(description = "알람 처리 상태 변경 요청")
    public record AlarmStatusUpdateRequest(
            @Schema(description = "변경할 상태", example = "ACKNOWLEDGED",
                    allowableValues = {"OPEN", "ACKNOWLEDGED", "RESOLVED"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String status
    ) {}

    private MonitoringDto() {}
}

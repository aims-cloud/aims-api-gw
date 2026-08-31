package com.innogrid.aims.monitoring.dto.agent;

import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import io.swagger.v3.oas.annotations.media.Schema;

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

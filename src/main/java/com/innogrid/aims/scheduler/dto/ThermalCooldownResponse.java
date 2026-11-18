package com.innogrid.aims.scheduler.dto;

import com.innogrid.aims.scheduler.model.NodeResource;

import io.swagger.v3.oas.annotations.media.Schema;

/** 냉각 대기 중인 노드 한 대. 키 순서는 성분 순서다. */
@Schema(description = "냉각 대기 노드 — 임계 온도를 넘겨 배치에서 잠시 빠진 노드와 남은 대기 시간")
public record ThermalCooldownResponse(
        @Schema(description = "MDC 식별자", example = "mdc-01") String mdcId,
        @Schema(description = "노드 식별자", example = "localhost:9100") String nodeId,
        @Schema(description = "현재 온도(℃). 수집하지 못하면 0", example = "0.0") double temperatureCelsius,
        @Schema(description = "온도 상태 NORMAL·WARNING·CRITICAL(문자열)", example = "NORMAL") String thermalState,
        @Schema(description = "남은 냉각 대기(분)", example = "10") long remainingCooldownMinutes) {

    /** 상태는 문자열로 옮긴다. */
    public static ThermalCooldownResponse of(NodeResource node, long remainingCooldownMinutes) {
        return new ThermalCooldownResponse(node.getMdcId(), node.getNodeId(), node.getTemperatureCelsius(),
                String.valueOf(node.getThermalState()), remainingCooldownMinutes);
    }
}

package com.innogrid.aims.scheduler.dto;

import java.util.List;
import java.util.SortedMap;

import io.swagger.v3.oas.annotations.media.Schema;

/** 냉각 대기 해제 결과. 키 순서는 성분 순서다. */
@Schema(description = "냉각 대기 해제 결과 — 지운 대기의 온도 상한과 해제 직전에 남아 있던 대기 시간")
public record ThermalCooldownReleaseResponse(
        @Schema(description = "노드 식별자", example = "localhost:9100") String nodeId,
        @Schema(description = "지운 냉각 대기의 온도 상한(℃) — 오름차순", example = "[75.0]") List<Double> releasedLimitsCelsius,
        @Schema(description = "해제 직전에 남아 있던 냉각 대기(분) — 목록의 remainingCooldownMinutes 와 같이 그 노드의 대기가 모두 풀릴 때까지였던 시간",
                example = "7") long remainingCooldownMinutes) {

    /** 상한 → 남은 분(오름차순)에서 만든다. 남은 분은 그 가운데 가장 긴 값이다. */
    public static ThermalCooldownReleaseResponse of(String nodeId, SortedMap<Double, Long> released) {
        return new ThermalCooldownReleaseResponse(nodeId, List.copyOf(released.keySet()),
                released.values().stream().mapToLong(Long::longValue).max().orElse(0));
    }
}

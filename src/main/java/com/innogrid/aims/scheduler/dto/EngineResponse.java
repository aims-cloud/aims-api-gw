package com.innogrid.aims.scheduler.dto;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/** 선택 가능한 스케줄링 엔진 한 줄. 키 순서는 성분 순서다. */
@Schema(description = "선택 가능한 스케줄링 엔진")
public record EngineResponse(
        @Schema(description = "엔진 식별자 — 배치·스케줄의 engine 값", example = "weighted") String id,
        @Schema(description = "엔진 이름(클래스 이름, 표시명 아님)", example = "WeightedScoreScheduler") String name) {

    /** 엔진 레지스트리의 설명 한 줄에서 옮긴다 — 레지스트리는 비교 판 설정 다이제스트의 입력이라 건드리지 않는다. */
    public static EngineResponse of(Map<String, String> described) {
        return new EngineResponse(described.get("id"), described.get("name"));
    }
}

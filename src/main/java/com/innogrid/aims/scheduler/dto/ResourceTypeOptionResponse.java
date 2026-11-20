package com.innogrid.aims.scheduler.dto;

import com.innogrid.aims.scheduler.entity.Workload;

import io.swagger.v3.oas.annotations.media.Schema;

/** 워크로드·스케줄 생성 화면의 리소스 종류 선택지 한 줄. 키 순서는 성분 순서다. */
@Schema(description = "리소스 종류 선택지 — 코드와 표시 이름(지금은 같은 값)")
public record ResourceTypeOptionResponse(
        @Schema(description = "리소스 종류 코드", example = "GPU") String code,
        @Schema(description = "표시 이름(지금은 코드와 같음)", example = "GPU") String label) {

    public static ResourceTypeOptionResponse of(Workload.ResourceType type) {
        return new ResourceTypeOptionResponse(type.name(), type.name());
    }
}

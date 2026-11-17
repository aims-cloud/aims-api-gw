package com.innogrid.aims.scheduler.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 소비자는 {@code PATCH /workloads/{id}/status}다. 상태는 문자열로 받아 기존 파서가
 * 대소문자를 무시해 해석한다 — 허용값에 배치 상태가 없는 이유는 배치가 이 API가 아니라
 * 스케줄링 엔진의 일이기 때문이다.
 */
@Schema(description = "워크로드 실행 상태 전이 요청(스케줄이 아니라 워크로드 API)")
public record StatusChangeRequest(
        @Schema(description = "바꿀 상태. 대소문자 무시. 전이표에 없는 조합은 400 — SCHEDULED 로는 바꿀 수 없고(배치는 엔진이 한다), "
                + "FAILED 는 PENDING 으로만 되살릴 수 있다",
                example = "RUNNING", allowableValues = {"RUNNING", "COMPLETED", "FAILED", "PENDING"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String status
) {}

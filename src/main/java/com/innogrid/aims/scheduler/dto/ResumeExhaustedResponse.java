package com.innogrid.aims.scheduler.dto;

import java.util.List;

import com.innogrid.aims.scheduler.entity.Workload;

import io.swagger.v3.oas.annotations.media.Schema;

/** 재시도 소진 워크로드 일괄 재개 결과. 키 순서는 성분 순서다. */
@Schema(description = "재시도 소진 워크로드 일괄 재개 결과")
public record ResumeExhaustedResponse(
        @Schema(description = "되살린 건수(= workloads 길이)", example = "0") int resumedCount,
        @Schema(description = "대기로 되살린 워크로드") List<WorkloadResponse> workloads) {

    public static ResumeExhaustedResponse of(List<Workload> resumed) {
        return new ResumeExhaustedResponse(resumed.size(), resumed.stream().map(WorkloadResponse::from).toList());
    }
}

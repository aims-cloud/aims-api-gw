package com.innogrid.aims.scheduler.dto;

import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.Workload;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 배치 예약 취소의 결과 — 대기로 돌아간 워크로드와 풀린 배정, 마감된 전달 기록.
 *
 * <p>취소는 실패가 아니다. 재시도 회계는 그대로이고 배치 이력도 남긴다. 풀린 노드·MDC는 워크로드 응답에서
 * 이미 null이 됐으므로 여기서 따로 알려 준다.</p>
 */
@Schema(description = "배치 예약 취소 결과 — 대기로 돌아간 워크로드 · 풀린 배정 · 마감된 전달 기록")
public record WorkloadReleaseResponse(
        @Schema(description = "취소 뒤 워크로드(23필드). status 는 PENDING, 배정 세 칸은 null, 재시도 회계는 불변")
        WorkloadResponse workload,
        @Schema(description = "풀린 배정의 MDC", example = "mdc-01")
        String releasedMdcId,
        @Schema(description = "풀린 배정의 노드", example = "localhost:9100")
        String releasedNodeId,
        @Schema(description = "현재 결정의 전달 기록(13필드). ACCEPTED 였으면 FAILED 로 마감되고 비고가 '예약 취소 — ' 로 시작(기존 비고는 뒤에). 이미 끝 상태였으면 그대로. 기록이 없으면 null")
        PlacementDispatchResponse dispatch
) {
    public static WorkloadReleaseResponse from(Workload workload, String releasedMdcId, String releasedNodeId,
                                               PlacementDispatch dispatch) {
        return new WorkloadReleaseResponse(WorkloadResponse.from(workload), releasedMdcId, releasedNodeId,
                dispatch == null ? null : PlacementDispatchResponse.from(dispatch));
    }
}

package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.NodePlacementHold;

import io.swagger.v3.oas.annotations.media.Schema;

/** 노드 배치 중지 응답 — 9필드. 필드 구성은 {@code NodePlacementHoldApiTest}가 확인한다. */
public record NodePlacementHoldResponse(
        @Schema(description = "원장 행 id", example = "1")
        Long id,
        @Schema(description = "배치를 중지한 노드 id(/nodes 의 nodeId 와 같은 값 · 대소문자 구분)", example = "node-a:9100")
        String nodeId,
        @Schema(description = "중지 사유. 배치 이력의 제외 사유 문장에 실린다. 없으면 null", example = "디스크 교체 점검")
        String reason,
        String createdBy,
        String createdByName,
        String updatedBy,
        String updatedByName,
        @Schema(description = "등록 시각(UTC) — 배치 중지 시작 시각. 사유를 고쳐도 그대로다")
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static NodePlacementHoldResponse from(NodePlacementHold h) {
        return new NodePlacementHoldResponse(h.getId(), h.getNodeId(), h.getReason(),
                h.getCreatedBy(), h.getCreatedByName(), h.getUpdatedBy(), h.getUpdatedByName(),
                h.getCreatedAt(), h.getUpdatedAt());
    }
}

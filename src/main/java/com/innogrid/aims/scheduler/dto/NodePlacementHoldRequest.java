package com.innogrid.aims.scheduler.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * 노드 배치 중지 등록 · 수정 요청.
 *
 * <p>등록(POST)은 {@code nodeId}가 필수다. 수정(PUT)은 사유만 바꾸는 부분 수정이다 — {@code reason} 키가 없으면 유지,
 * 명시적 null이면 비움, 문자열이면 그 값. 노드를 바꾸면 중지 시작 시각({@code createdAt})의 뜻이 깨지므로 수정에서
 * {@code nodeId}는 지금 값과 같을 때만 받는다. 검증은 {@code NodePlacementHoldService}가 한다.</p>
 */
public record NodePlacementHoldRequest(
        @Schema(description = "배치를 중지할 노드 id(/nodes 의 nodeId · 앞뒤 공백은 뺌 · 대소문자 구분 · 최대 100자). "
                + "등록에 필수, 수정에서는 지금 값과 같을 때만", example = "localhost:9100")
        String nodeId,
        @Schema(description = "중지 사유(최대 300자 · 문자열만). 수정에서 키를 빼면 유지, null 이면 비움",
                type = "string", nullable = true, example = "디스크 교체 점검")
        JsonNode reason
) {}

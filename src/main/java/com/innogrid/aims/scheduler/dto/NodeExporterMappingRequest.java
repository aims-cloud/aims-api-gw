package com.innogrid.aims.scheduler.dto;

import tools.jackson.databind.JsonNode;

/**
 * 익스포터 대응 생성·수정 요청.
 *
 * <p>수정(PUT)은 다른 원장과 같은 <b>부분 수정</b>이다 — 요청에 있는 키만 반영하고 없는 키는 유지한다. 두 익스포터
 * 칸과 설명은 "비우는 것"에 의미가 있어 {@link JsonNode} 로 받는다: 필드가 {@code null} 이면 키 없음(유지),
 * {@code isNull()} 이면 명시적 null(비움), 아니면 값. 익스포터 칸을 비우면 수집기가 호스트명 일치로 대체한다.</p>
 */
public record NodeExporterMappingRequest(
        String nodeId,
        JsonNode dcgmInstance,
        JsonNode ipmiInstance,
        JsonNode description
) {}

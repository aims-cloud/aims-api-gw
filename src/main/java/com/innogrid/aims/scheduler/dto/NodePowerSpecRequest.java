package com.innogrid.aims.scheduler.dto;

import tools.jackson.databind.JsonNode;

/**
 * 노드 전력 사양 생성·수정 요청.
 *
 * <p>수정(PUT)은 링크 원장·정책과 같은 <b>부분 수정</b>이다 — 요청에 있는 키만 반영하고 없는 키는 유지한다.
 * {@code description} 만 "비우는 것"에 의미가 있어 {@link JsonNode} 로 받는다: 필드가 {@code null} 이면
 * 키 없음(유지), {@code isNull()} 이면 명시적 null(비움), 아니면 값.</p>
 *
 * <p>검증은 {@code NodePowerSpecService} 가 한다 — 노드 식별자 필수·중복 금지, 정격은 0 보다 크고 유한,
 * 출처는 대소문자 무시. <b>정격의 크기 상한은 두지 않는다</b>(실데이터가 없어 추측이 되므로).</p>
 */
public record NodePowerSpecRequest(
        String nodeId,
        Double capacityWatts,
        String source,
        JsonNode description
) {}

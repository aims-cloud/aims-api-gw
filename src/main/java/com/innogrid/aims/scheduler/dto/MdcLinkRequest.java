package com.innogrid.aims.scheduler.dto;

import tools.jackson.databind.JsonNode;

/**
 * MDC 링크 생성·수정 요청.
 *
 * <p>수정(PUT)은 정책과 같은 <b>부분 수정</b>이다 — 요청에 있는 키만 반영하고 없는 키는 유지한다.
 * {@code mdcA}·{@code mdcB}는 둘 다 주어야 쌍을 바꾼다(한쪽만 주면 400). 순서는 상관없다 — 저장할 때
 * 사전순으로 정렬한다.</p>
 *
 * <p>세 필드는 "비우는 것"에도 의미가 있다 — 대역폭·손실률은 "모른다"로, 설명은 없음으로 돌릴 수 있다.
 * record의 일반 필드는 "키 없음"과 "명시적 null"을 구분하지 못하므로({@code PartialUpdateNullSemanticsTest})
 * 이 셋만 {@link JsonNode}로 받는다: 필드가 {@code null}이면 키 없음(유지), {@code isNull()}이면
 * 명시적 null(비움), 아니면 값.</p>
 *
 * <p>검증은 {@code MdcLinkService}가 한다 — 서로 다른 MDC, 지연 0 이상, 대역폭 0 초과, 손실률 0~100,
 * 상태·출처는 대소문자 무시.</p>
 */
public record MdcLinkRequest(
        String mdcA,
        String mdcB,
        Double latencyMs,

        JsonNode bandwidthMbps,
        JsonNode packetLossPercent,
        JsonNode description,

        String status,
        String source
) {}

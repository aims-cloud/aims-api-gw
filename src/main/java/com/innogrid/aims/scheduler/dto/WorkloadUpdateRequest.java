package com.innogrid.aims.scheduler.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * 워크로드 편집 요청 — <b>부분 갱신</b>이다. 요청에 있는 키만 반영하고 없는 키는 유지한다.
 *
 * <p>칸 이름은 생성 요청({@link WorkloadRequest})과 같다. 생성 DTO 를 재사용하지 않은 이유는 "비우기" 다 — 설명·MDC
 * 두 칸·전력 상한·온도 상한·요구 대역폭 여섯 칸은 값이 없는 상태에도 뜻이 있어 되돌릴 수 있어야 하는데, record 의 일반
 * 필드는 "키 없음" 과 "명시적 null" 을 구분하지 못한다({@code PartialUpdateNullSemanticsTest}). 그래서 이 여섯 칸만
 * {@link JsonNode} 로 받는다: 필드가 {@code null} 이면 키 없음(유지), {@code isNull()} 이면 명시적 null(비움), 아니면 값.
 * MDC 링크 원장 수정과 같은 규칙이다.</p>
 *
 * <p>나머지 일곱 칸(이름·요구량 넷·종류·우선순위)은 비울 수 없어 {@code null} 이면 유지한다. 상태·배정·재시도·시각 같은
 * 편집 대상이 아닌 키는 모르는 키처럼 무시된다 — 상세 조회 응답을 그대로 보내면 아무것도 바뀌지 않는다.
 * 검증은 {@code WorkloadService} 가 생성과 같은 규칙으로 한다.</p>
 */
@Schema(description = "워크로드 편집 요청(부분 갱신) — 보낸 키만 반영. 비울 수 있는 여섯 칸은 null 이면 비움, 나머지 null 은 유지")
public record WorkloadUpdateRequest(
        @Schema(description = "워크로드 이름(1~100자 · 앞뒤 공백 제거). null 이면 유지", example = "llm-batch")
        String name,
        @Schema(description = "설명(300자 이내 · 문자열만 · 앞뒤 공백 유지). null 이면 비움, 키를 빼면 유지", type = "string", nullable = true)
        JsonNode description,
        @Schema(description = "요구 CPU(1 이상). null 이면 유지", example = "2")
        Integer requiredCpu,
        @Schema(description = "요구 메모리 MB(1 이상). null 이면 유지", example = "2048")
        Integer requiredMemoryMb,
        @Schema(description = "요구 GPU(0 이상). null 이면 유지 — 없애려면 0", example = "0")
        Integer requiredGpu,
        @Schema(description = "요구 디스크 GB(0 이상). null 이면 유지 — 없애려면 0", example = "0")
        Integer requiredDiskGb,
        @Schema(description = "리소스 종류(대소문자 무시). null 이면 유지 — 비울 수 없음", example = "VM")
        String resourceType,
        @Schema(description = "데이터 MDC(50자 이내 · 문자열만 · 앞뒤 공백 제거 · 빈 문자열은 비움). null 이면 비움", type = "string", nullable = true)
        JsonNode dataMdcId,
        @Schema(description = "선호 MDC(50자 이내 · 문자열만 · 앞뒤 공백 제거 · 빈 문자열은 비움). null 이면 비움", type = "string", nullable = true)
        JsonNode preferredMdcId,
        @Schema(description = "노드 전력 상한 W(유한한 0 초과 숫자 · 숫자 문자열은 400). null 이면 비움", type = "number", nullable = true)
        JsonNode maxPowerBudgetWatts,
        @Schema(description = "온도 상한 °C(유한한 0 초과 숫자 · 숫자 문자열은 400). null 이면 비움", type = "number", nullable = true)
        JsonNode maxTemperatureCelsius,
        @Schema(description = "요구 대역폭 Mbps(유한한 0 초과 숫자 · 숫자 문자열은 400). null 이면 비움", type = "number", nullable = true)
        JsonNode requiredBandwidthMbps,
        @Schema(description = "우선순위 LOW·NORMAL·HIGH·CRITICAL(대소문자 무시). null 이면 유지", example = "HIGH")
        String priority
) {}

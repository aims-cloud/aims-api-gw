package com.innogrid.aims.scheduler.dto;

import com.innogrid.aims.scheduler.entity.Workload;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * 스케줄 편집 요청 — <b>부분 갱신</b>이다. 요청에 있는 키만 반영하고 없는 키는 유지한다.
 *
 * <p>칸 이름은 생성 요청({@link ScheduleRequest})과 같다. 생성 DTO를 그대로 쓰지 않는 이유는 "비우기"다 — 지정 정책·
 * 엔진·전력 상한·온도 상한·설명 다섯 칸은 값이 없는 상태(상속 · 제약 없음)에도 뜻이 있어 되돌릴 수 있어야 하는데, record의
 * 일반 필드는 "키 없음"과 "명시적 null"을 구분하지 못한다. 그래서 이 다섯 칸만 {@link JsonNode}로 받는다: 필드가
 * {@code null}이면 키 없음(유지), {@code isNull()}이면 명시적 null(비움), 아니면 값. 워크로드 편집·원장 수정과 같은 규칙이다.</p>
 *
 * <p>나머지 일곱 칸은 비울 수 없어 {@code null}이면 유지한다 — 배치 순서는 비워도 응답이 기본값으로 해석해 돌려주므로
 * {@code "age"}를 보내면 되고, 리소스 종류는 비우면 VM 해석과 같다. 검증은 {@code ScheduleService}가 생성과 같은 규칙으로
 * 한 뒤 한꺼번에 반영한다.</p>
 */
@Schema(description = "스케줄 편집 요청(부분 갱신) — 보낸 키만 반영. 정책·엔진·전력 상한·온도 상한·설명은 null 이면 비움, 나머지 null 은 유지")
public record ScheduleUpdateRequest(
        @Schema(description = "업무 키(화면의 스케줄 ID). null 이면 유지. 보내면 기존 값과 같아야 하고 다르면 400", example = "nightly-vm-02")
        String scheduleId,
        @Schema(description = "스케줄명, 100자 이내. null 이면 유지, 공백이면 400", example = "야간 VM 배치")
        String name,
        @Schema(description = "배치 대상 리소스 종류. null 이면 유지 — 비울 수 없음")
        Workload.ResourceType resourceType,
        @Schema(description = "실행 주기(Quartz 6필드). null 이면 유지. 바꾸면 다음 실행 시각을 다시 계산한다", example = "0 0 2 * * ?")
        String cronExpression,
        @Schema(description = "자동 실행 여부. null 이면 유지")
        Boolean autoRun,
        @Schema(description = "사용 여부. null 이면 유지")
        Boolean enabled,
        @Schema(description = "설명(300자 이내 · 문자열만 · 앞뒤 공백 제거). null 이나 빈 문자열이면 비움, 키를 빼면 유지",
                type = "string", nullable = true)
        JsonNode description,
        @Schema(description = "실행에 쓸 스케줄링 엔진 id(문자열만 · 등록된 값). null 이나 공백이면 비움 — 정책 기본 엔진, 그것도 없으면 weighted. 키를 빼면 유지",
                type = "string", nullable = true, example = "power", allowableValues = {"weighted", "power", "temperature"})
        JsonNode engine,
        @Schema(description = "적용할 배치 정책 id(정수만 · 숫자 문자열은 400). null 이면 비움 — 리소스 종류별 활성 정책을 따른다. 키를 빼면 유지. 없는 id 는 검사하지 않고 실행 시 404",
                type = "integer", format = "int64", nullable = true)
        JsonNode policyId,
        @Schema(description = "노드 전력 상한 W(숫자만 · 숫자 문자열은 400). null 이면 비움 — 제약을 쓰지 않음. 키를 빼면 유지",
                type = "number", nullable = true)
        JsonNode maxPowerBudgetWatts,
        @Schema(description = "온도 상한 ℃(숫자만 · 숫자 문자열은 400). null 이면 비움 — 정책 값을 따른다. 키를 빼면 유지",
                type = "number", nullable = true)
        JsonNode maxTemperatureCelsius,
        @Schema(description = "대상 워크로드의 배치 순서. null 이면 유지 — 기본으로 되돌리려면 age. 대소문자 무시, size/age 도 받는다",
                example = "PRIORITY_THEN_SIZE", allowableValues = {"PRIORITY_THEN_AGE", "PRIORITY_THEN_SIZE"})
        String placementOrder
) {}

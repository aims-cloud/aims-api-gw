package com.innogrid.aims.scheduler.dto;

import com.innogrid.aims.scheduler.entity.Workload;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 화면 폼 항목에 명세가 요구하는 확장 항목을 더한 형태다. 확장은 전부 선택이라 화면 폼만으로
 * 만들어도 동작한다 — 비워 두면 리소스 종류별 활성 정책과 그 정책의 기본 엔진을 쓴다.
 *
 * <p>검증은 서비스 계층에서 한다. 저장소의 최신 DTO 관례를 따라 검증 애너테이션을 두지
 * 않았고, 그래야 오류가 400 으로 일관되게 나간다. 필수 표기는 생성(POST) 기준이고, 저장 전 미리보기 본문으로도 쓴다.
 * 편집(PUT)은 {@link ScheduleUpdateRequest} 가 받는다.</p>
 */
@Schema(description = "스케줄 생성 요청(저장 전 미리보기 본문 겸용). 스케줄 ID·스케줄명·실행 주기가 필수")
public record ScheduleRequest(
        @Schema(description = "업무 키(화면의 스케줄 ID). 영문·숫자로 시작하는 영문·숫자·_ . - 50자 이내. "
                + "생성 시 필수, 중복이면 409",
                example = "nightly-vm-02", requiredMode = Schema.RequiredMode.REQUIRED)
        String scheduleId,
        @Schema(description = "스케줄명, 100자 이내. 생성 시 필수", example = "야간 VM 배치",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "배치 대상 리소스 종류. 생성 시 비우면 저장은 비고 실행 때 VM 으로 해석한다")
        Workload.ResourceType resourceType,
        @Schema(description = "실행 주기. Quartz 6필드(초 분 시 일 월 요일), 한국 시간 기준으로 해석, 120자 이내, "
                + "최소 간격 60초. 연도 필드·C·일과 요일을 둘 다 구체값으로 지정(예: 0 0 2 1 * MON)하면 400 — 한쪽이 * 또는 ? 면 허용. 생성 시 필수",
                example = "0 0 2 * * ?", requiredMode = Schema.RequiredMode.REQUIRED)
        String cronExpression,
        @Schema(description = "자동 실행 여부. 생성 시 비우면 false")
        Boolean autoRun,
        @Schema(description = "사용 여부. 끄면 자동 실행과 즉시 실행이 모두 막힌다(즉시 실행은 409). "
                + "생성 시 비우면 true")
        Boolean enabled,
        @Schema(description = "설명, 300자 이내")
        String description,

        @Schema(description = "실행에 쓸 스케줄링 엔진 id. 생성 시 비우면 정책의 기본 엔진, 그것도 없으면 weighted"
                + ". 모르는 값은 400",
                example = "power", allowableValues = {"weighted", "power", "temperature"})
        String engine,
        @Schema(description = "적용할 배치 정책 id. 생성 시 비우면 리소스 종류별 활성 정책. "
                + "없는 id 는 생성 시 검사하지 않고 실행 시 404 가 된다")
        Long policyId,
        @Schema(description = "이번 실행에서만 적용할 노드 전력 상한(W). 현재 소비 전력이 이 값을 넘는 노드는 후보에서 "
                + "제외한다. 비우면 이 제약을 쓰지 않는다")
        Double maxPowerBudgetWatts,
        @Schema(description = "정책의 온도 상한(℃)을 이 스케줄에서만 덮어쓴다. 생성 시 비우면 정책 값")
        Double maxTemperatureCelsius,
        @Schema(description = "대상 워크로드의 배치 순서. 기본은 우선순위 → 등록 순이고, 다른 하나는 우선순위 → 요구 "
                + "CPU·메모리가 큰 것부터다. 대소문자 무시, size/age 도 받는다",
                example = "PRIORITY_THEN_SIZE", allowableValues = {"PRIORITY_THEN_AGE", "PRIORITY_THEN_SIZE"})
        String placementOrder
) {
    /** 배치 순서를 지정하지 않는 형태 — 배치 순서 필드가 생기기 전의 호출자(기존 테스트)와 호환. */
    public ScheduleRequest(String scheduleId, String name, Workload.ResourceType resourceType, String cronExpression,
                           Boolean autoRun, Boolean enabled, String description, String engine, Long policyId,
                           Double maxPowerBudgetWatts, Double maxTemperatureCelsius) {
        this(scheduleId, name, resourceType, cronExpression, autoRun, enabled, description, engine, policyId,
             maxPowerBudgetWatts, maxTemperatureCelsius, null);
    }

    public boolean resolveAutoRun() { return autoRun != null && autoRun; }

    public boolean resolveEnabled() { return enabled == null || enabled; }
}

package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.Workload;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 목록 10컬럼과 하단 상세 탭이 같은 필드를 쓰고, 상세에만 있는 항목도 목록 응답에
 * 이미 들어 있어 한 형태로 합쳤다. 화면 상세 카드에 리소스 종류가 빠져 있지만 API는
 * 내려준다 — 프런트가 채우기만 하면 된다.
 *
 * <p>표시 시각 변환(UTC+9)은 프런트가 한다.</p>
 */
@Schema(description = "스케줄 목록 1행이자 상세 응답. 시각은 전부 UTC")
public record ScheduleResponse(
        @Schema(description = "스케줄 PK. 상세·편집·즉시 실행·실행 이력 경로의 {id}. 삭제는 ids 쿼리 파라미터로 여러 건을 함께 보낸다")
        Long id,
        @Schema(description = "사용자가 정한 업무 키(화면의 스케줄 ID). 생성 후 바꿀 수 없다", example = "nightly-vm-02")
        String scheduleId,
        @Schema(description = "스케줄명")
        String name,
        @Schema(description = "배치 대상 리소스 종류. 저장값 그대로라 비어 있을 수 있고, 그때 실행은 VM 으로 해석한다")
        Workload.ResourceType resourceType,
        @Schema(description = "입력한 실행 주기 원문(Quartz 표기). 한국 시간 기준으로 해석된다", example = "0 0 2 * * ?")
        String cronExpression,
        @Schema(description = "자동 실행 여부")
        boolean autoRun,
        @Schema(description = "사용 여부. 끄면 자동 실행과 즉시 실행이 모두 막힌다")
        boolean enabled,
        @Schema(description = "설명")
        String description,

        @Schema(description = "이 스케줄에 지정한 엔진 id. 비어 있으면 정책의 기본 엔진, 그것도 없으면 weighted 를 쓴다")
        String engine,
        @Schema(description = "지정한 배치 정책 id. 비어 있으면 리소스 종류별 활성 정책")
        Long policyId,
        @Schema(description = "지정한 노드 전력 상한(W). 비어 있으면 이 제약을 쓰지 않는다")
        Double maxPowerBudgetWatts,
        @Schema(description = "온도 상한(℃) 덮어쓰기. 비어 있으면 정책 값")
        Double maxTemperatureCelsius,
        @Schema(description = "해석된 배치 순서. 저장이 비어 있으면 PRIORITY_THEN_AGE")
        Schedule.PlacementOrder placementOrder,

        @Schema(description = "최근 실행의 처리 상태(화면 배지). 실행 중이면 RUNNING, 실행 전이면 null")
        ScheduleRun.RunStatus lastRunStatus,
        @Schema(description = "최근 실행 시작 시각(UTC, 화면의 시작 시간). 실행 전이면 null")
        OffsetDateTime lastRunAt,
        @Schema(description = "최근 실행 종료 시각(UTC, 화면의 종료 시간). 실행 중이거나 실행 전이면 null")
        OffsetDateTime lastRunFinishedAt,
        @Schema(description = "최근 실행 결과 메시지. 실행 중이거나 실행 전이면 null. "
                + "자동 실행이 대상 0건으로 끝난 회차는 이력 행이 없고 이 메시지만 남는다")
        String lastRunMessage,
        @Schema(description = "다음 자동 실행 예정 시각(UTC). 자동 실행과 사용 여부가 모두 켜져 있을 때만 값이 있다")
        OffsetDateTime nextRunAt,

        @Schema(description = "생성자 로그인 계정(email)", example = "user@innogrid.com")
        String createdBy,
        @Schema(description = "생성 시점의 사용자 이름. 화면은 이름(email) 로 조합해 표시한다. 사용자 원장에 없으면 null")
        String createdByName,
        @Schema(description = "생성 시각(UTC)")
        OffsetDateTime createdAt,
        @Schema(description = "최종 수정자 로그인 계정(email)")
        String updatedBy,
        @Schema(description = "최종 수정 시점의 사용자 이름. 사용자 원장에 없으면 null")
        String updatedByName,
        @Schema(description = "최종 수정 시각(UTC)")
        OffsetDateTime updatedAt
) {
    public static ScheduleResponse from(Schedule s) {
        return new ScheduleResponse(
                s.getId(), s.getScheduleId(), s.getName(), s.getResourceType(),
                s.getCronExpression(), s.isAutoRun(), s.isEnabled(), s.getDescription(),
                s.getEngine(), s.getPolicyId(), s.getMaxPowerBudgetWatts(), s.getMaxTemperatureCelsius(),
                s.resolvePlacementOrder(),
                s.getLastRunStatus(), s.getLastRunAt(), s.getLastRunFinishedAt(), s.getLastRunMessage(),
                s.getNextRunAt(),
                s.getCreatedBy(), s.getCreatedByName(), s.getCreatedAt(),
                s.getUpdatedBy(), s.getUpdatedByName(), s.getUpdatedAt());
    }
}

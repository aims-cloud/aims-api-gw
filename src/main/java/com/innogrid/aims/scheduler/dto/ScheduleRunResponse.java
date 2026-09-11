package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.ScheduleRun;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 실행 이력 탭의 5컬럼(스케줄 ID·시작·종료·처리 상태·메시지)에 집계 건수를 함께 내려준다.
 * 즉시 실행의 응답도 이 형태다 — 실행 한 번의 기록은 어디서 보든 같은 열이어야 한다.
 *
 * <p>자동 실행이 대상 0건으로 끝난 경우에는 이 행이 만들어지지 않는다. 그 사실은
 * 스케줄 응답의 최근 실행 메시지에서 확인할 수 있다.</p>
 */
@Schema(description = "스케줄 실행 1회의 기록(즉시 실행 응답·실행 이력 1행). 자동 실행이 대상 0건으로 끝난 회차는 행이 없다(즉시 실행은 남는다)")
public record ScheduleRunResponse(
        @Schema(description = "실행 PK. 실행 상세 경로의 {runId}")
        Long runId,
        @Schema(description = "스케줄 PK(스케줄 응답의 id). 화면의 스케줄 ID 는 이 값이 아니라 scheduleKey 다")
        Long scheduleId,
        @Schema(description = "실행 시점의 스케줄 업무 키(화면의 스케줄 ID)")
        String scheduleKey,
        @Schema(description = "실행 계기. AUTO 는 실행 주기에 따른 자동 실행, MANUAL 은 즉시 실행")
        ScheduleRun.TriggerType triggerType,
        @Schema(description = "처리 상태. RUNNING 은 진행 중(또는 중단 뒤 회수 전)인 행")
        ScheduleRun.RunStatus status,
        @Schema(description = "실행 시작 시각(UTC)")
        OffsetDateTime startedAt,
        @Schema(description = "실행 종료 시각(UTC). 진행 중(RUNNING)이면 null")
        OffsetDateTime finishedAt,
        @Schema(description = "실행 결과 메시지")
        String message,
        @Schema(description = "이번 실행의 대상 워크로드 수. 1회 상한을 넘으면 상한까지만 센다")
        int targetCount,
        @Schema(description = "배치에 성공한 워크로드 수")
        int successCount,
        @Schema(description = "배치에 실패한 워크로드 수")
        int failedCount,
        @Schema(description = "대상이 1회 실행 상한(서버 설정)을 넘어 일부만 처리했는지")
        boolean truncated,
        @Schema(description = "실행에 쓴 엔진 id", example = "power")
        String engineId,
        @Schema(description = "실행에 쓴 배치 정책 이름")
        String policyName,
        @Schema(description = "실행 주체. 즉시 실행은 사용자 email, 자동 실행은 SYSTEM")
        String triggeredBy
) {
    public static ScheduleRunResponse from(ScheduleRun r) {
        return new ScheduleRunResponse(
                r.getId(), r.getScheduleId(), r.getScheduleKey(),
                r.getTriggerType(), r.getStatus(), r.getStartedAt(), r.getFinishedAt(),
                r.getMessage(), r.getTargetCount(), r.getSuccessCount(), r.getFailedCount(),
                r.isTruncated(), r.getEngineId(), r.getPolicyName(), r.getTriggeredBy());
    }
}

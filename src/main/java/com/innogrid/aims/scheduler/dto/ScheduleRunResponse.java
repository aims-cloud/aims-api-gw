package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.ScheduleRun;

/**
 * 실행 이력 1행 — 실행 이력 탭의 5컬럼(스케줄 ID·시작·종료·처리 상태·메시지)과
 * 집계 건수를 함께 내려준다.
 *
 * <p>자동 실행이 대상 0건으로 끝난 경우에는 이 행이 만들어지지 않는다. 그 사실은
 * 스케줄 응답의 {@code lastRunMessage} 에서 확인할 수 있다.</p>
 */
public record ScheduleRunResponse(
        Long runId,
        Long scheduleId,
        String scheduleKey,
        ScheduleRun.TriggerType triggerType,     // AUTO | MANUAL
        ScheduleRun.RunStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String message,
        int targetCount,
        int successCount,
        int failedCount,
        boolean truncated,                        // 상한에 걸려 남은 대상이 있는지
        String engineId,
        String policyName,
        String triggeredBy                        // 자동 실행은 SYSTEM
) {
    public static ScheduleRunResponse from(ScheduleRun r) {
        return new ScheduleRunResponse(
                r.getId(), r.getScheduleId(), r.getScheduleKey(),
                r.getTriggerType(), r.getStatus(), r.getStartedAt(), r.getFinishedAt(),
                r.getMessage(), r.getTargetCount(), r.getSuccessCount(), r.getFailedCount(),
                r.isTruncated(), r.getEngineId(), r.getPolicyName(), r.getTriggeredBy());
    }
}

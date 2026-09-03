package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.Workload;

/**
 * 스케줄 목록 1행이자 상세 응답.
 *
 * <p>목록 10컬럼과 하단 상세 탭이 같은 필드를 쓰고, 상세에만 있는 항목도 목록 응답에
 * 이미 들어 있어 한 형태로 합쳤다. 화면 상세 카드에 리소스 종류가 빠져 있지만 API 는
 * 내려준다 — 프런트가 채우기만 하면 된다.</p>
 *
 * <p>시각은 전부 UTC 다. 화면 표시는 프런트가 UTC+9 로 바꾼다. {@code cronExpression} 은
 * 사용자가 입력한 Quartz 원문이며 한국 시간 기준으로 해석된다.</p>
 */
public record ScheduleResponse(
        Long id,
        String scheduleId,
        String name,
        Workload.ResourceType resourceType,
        String cronExpression,
        boolean autoRun,
        boolean enabled,
        String description,

        String engine,
        Long policyId,
        Double maxPowerBudgetWatts,
        Double maxTemperatureCelsius,
        Schedule.PlacementOrder placementOrder, // 해석된 값 — 저장이 null 이면 PRIORITY_THEN_AGE

        ScheduleRun.RunStatus lastRunStatus,    // 처리 상태 배지
        OffsetDateTime lastRunAt,               // 시작 시간
        OffsetDateTime lastRunFinishedAt,       // 종료 시간
        String lastRunMessage,
        OffsetDateTime nextRunAt,               // 다음 실행 예정. 자동 실행이 꺼져 있으면 null

        String createdBy,                       // email
        String createdByName,                   // 화면에는 "이름(email)" 로 조합해 표시
        OffsetDateTime createdAt,
        String updatedBy,
        String updatedByName,
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

package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.Workload;

/**
 * 워크로드 응답 — {@code /scheduler/workloads} 4종이 공유한다.
 *
 * <p>필드 22개(원래 21개 + {@code lastFailedAt})는 {@link Workload} 엔티티를 그대로 직렬화하던 계약과 <b>동일</b>하다
 * ({@code ResponseJsonGoldenTest} 가 고정). 엔티티에 컬럼이나 {@code get} 메서드를 더해도 이 응답은
 * 변하지 않는다 — API 형태의 주인은 이 record 다.</p>
 *
 * <p>{@code retryCount} 는 기존 행이 null 일 수 있어 {@code Integer} 로 둔다. 0 으로 바꾸면 계약 위반.</p>
 *
 * <p>{@code lastFailedAt} 은 마지막 배치 실패 시각이다. 재시도 백오프가 이 값을 보고 다시 시도할지
 * 정하므로, 화면에서 "왜 아직 대상이 아닌가" 를 설명할 때 쓴다. 실패한 적이 없거나 되살린
 * 워크로드는 null 이다.</p>
 */
public record WorkloadResponse(
        Long id,
        String name,
        String description,
        int requiredCpu,
        int requiredMemoryMb,
        int requiredGpu,
        int requiredDiskGb,
        Workload.ResourceType resourceType,
        String dataMdcId,
        String preferredMdcId,
        Double maxPowerBudgetWatts,
        Double maxTemperatureCelsius,
        Workload.WorkloadPriority priority,
        String assignedMdcId,
        String assignedNodeId,
        Workload.WorkloadStatus status,
        Double schedulingScore,
        String schedulingReason,
        Integer retryCount,
        OffsetDateTime lastFailedAt,
        OffsetDateTime createdAt,
        OffsetDateTime scheduledAt
) {
    public static WorkloadResponse from(Workload w) {
        return new WorkloadResponse(
                w.getId(), w.getName(), w.getDescription(),
                w.getRequiredCpu(), w.getRequiredMemoryMb(), w.getRequiredGpu(), w.getRequiredDiskGb(),
                w.getResourceType(), w.getDataMdcId(), w.getPreferredMdcId(),
                w.getMaxPowerBudgetWatts(), w.getMaxTemperatureCelsius(), w.getPriority(),
                w.getAssignedMdcId(), w.getAssignedNodeId(), w.getStatus(),
                w.getSchedulingScore(), w.getSchedulingReason(), w.getRetryCount(),
                w.getLastFailedAt(), w.getCreatedAt(), w.getScheduledAt());
    }
}

package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.PlacementDispatch;

/** 디스패치 기록 응답 — 13필드. 계약은 {@code PlacementDispatchApiTest}가 고정한다. */
public record PlacementDispatchResponse(
        Long id,
        Long workloadId,
        String workloadName,
        Long scheduleRunId,
        String mdcId,
        String nodeId,
        PlacementDispatch.DispatchStatus status,
        String idempotencyKey,
        String externalRef,
        String message,
        OffsetDateTime requestedAt,
        OffsetDateTime respondedAt,
        String createdBy
) {
    public static PlacementDispatchResponse from(PlacementDispatch d) {
        return new PlacementDispatchResponse(d.getId(), d.getWorkloadId(), d.getWorkloadName(), d.getScheduleRunId(),
                d.getMdcId(), d.getNodeId(), d.getStatus(), d.getIdempotencyKey(), d.getExternalRef(), d.getMessage(),
                d.getRequestedAt(), d.getRespondedAt(), d.getCreatedBy());
    }
}

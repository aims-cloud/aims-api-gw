package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.NodePowerSpec;

/** 노드 전력 사양 응답 — 11필드. 계약은 {@code NodePowerSpecApiTest} 가 고정한다. */
public record NodePowerSpecResponse(
        Long id,
        String nodeId,
        double capacityWatts,
        NodePowerSpec.PowerSpecSource source,
        String description,
        String createdBy,
        String createdByName,
        String updatedBy,
        String updatedByName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static NodePowerSpecResponse from(NodePowerSpec s) {
        return new NodePowerSpecResponse(s.getId(), s.getNodeId(), s.getCapacityWatts(), s.getSource(), s.getDescription(),
                s.getCreatedBy(), s.getCreatedByName(), s.getUpdatedBy(), s.getUpdatedByName(),
                s.getCreatedAt(), s.getUpdatedAt());
    }
}

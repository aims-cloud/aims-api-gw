package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.NodeExporterMapping;

/** 익스포터 대응 응답 — 11필드. 계약은 {@code NodeExporterMappingApiTest}가 고정한다. */
public record NodeExporterMappingResponse(
        Long id,
        String nodeId,
        String dcgmInstance,
        String ipmiInstance,
        String description,
        String createdBy,
        String createdByName,
        String updatedBy,
        String updatedByName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static NodeExporterMappingResponse from(NodeExporterMapping m) {
        return new NodeExporterMappingResponse(m.getId(), m.getNodeId(), m.getDcgmInstance(), m.getIpmiInstance(),
                m.getDescription(), m.getCreatedBy(), m.getCreatedByName(), m.getUpdatedBy(), m.getUpdatedByName(),
                m.getCreatedAt(), m.getUpdatedAt());
    }
}

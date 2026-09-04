package com.innogrid.aims.scheduler.dto;

import java.time.OffsetDateTime;

import com.innogrid.aims.scheduler.entity.MdcLink;

/**
 * MDC 링크 응답 — {@code /scheduler/mdc-links} 전체와 매트릭스 칸이 공유한다. 필드 15개.
 *
 * <p>{@code mdcA} 는 항상 사전순으로 작은 쪽이다. 화면이 "출발·도착"으로 읽지 않도록 이름을 A·B 로 두었다.</p>
 */
public record MdcLinkResponse(
        Long id,
        String mdcA,
        String mdcB,
        double latencyMs,
        Double bandwidthMbps,
        Double packetLossPercent,
        MdcLink.LinkStatus status,
        MdcLink.LinkSource source,
        String description,
        String createdBy,
        String createdByName,
        String updatedBy,
        String updatedByName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static MdcLinkResponse from(MdcLink l) {
        return new MdcLinkResponse(
                l.getId(), l.getMdcA(), l.getMdcB(), l.getLatencyMs(), l.getBandwidthMbps(), l.getPacketLossPercent(),
                l.getStatus(), l.getSource(), l.getDescription(),
                l.getCreatedBy(), l.getCreatedByName(), l.getUpdatedBy(), l.getUpdatedByName(),
                l.getCreatedAt(), l.getUpdatedAt());
    }
}

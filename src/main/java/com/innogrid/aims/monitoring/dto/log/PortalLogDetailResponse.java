package com.innogrid.aims.monitoring.dto.log;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.entity.PortalLogEntry;

/**
 * 관측 > 로그 관리 상세 — 목록 항목에 사용자 ID·접속 IP·연관 원장·요청 추적 정보를 더한다 (docs/08 §8).
 * 화면의 "관리자 (superadmin)" 조합은 userName·userId 로 프론트가 만든다.
 * refType/refId 가 있으면 연관 화면(알람 상세 등)으로 이동할 수 있다.
 */
public record PortalLogDetailResponse(
        Long logId,
        PortalLogType type,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        String userId,
        String userName,
        String clientIp,
        String screen,
        String action,
        String content,
        PortalLogResult result,
        String description,
        String refType,
        String refId,
        String requestId,
        String httpMethod,
        String requestUri
) {
    public static PortalLogDetailResponse from(PortalLogEntry e) {
        return new PortalLogDetailResponse(e.getId(), e.getType(), e.getStartedAt(), e.getCompletedAt(),
                e.getUserId(), e.getUserName(), e.getClientIp(), e.getScreen(), e.getAction(), e.getContent(),
                e.getResult(), e.getDescription(), e.getRefType(), e.getRefId(), e.getRequestId(),
                e.getHttpMethod(), e.getRequestUri());
    }
}

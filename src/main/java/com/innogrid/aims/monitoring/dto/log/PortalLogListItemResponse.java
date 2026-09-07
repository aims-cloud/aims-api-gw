package com.innogrid.aims.monitoring.dto.log;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.entity.PortalLogEntry;

/**
 * 관측 > 로그 관리 목록 1행 — 생성/완료 일시, 사용자, 화면, 작업, 내용, 결과, 설명 (docs/08 §8).
 * 시각은 ISO 원본으로 내려주고 표시 포맷은 프론트가 맡는다. screen/completedAt/description 이 null 이면 화면은 "-".
 * 시스템 이벤트(SYSTEM)는 userName 이 null 이라 화면은 "시스템"으로 표시한다.
 */
public record PortalLogListItemResponse(
        Long logId,
        PortalLogType type,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        String userName,
        String screen,
        String action,
        String content,
        PortalLogResult result,
        String description
) {
    public static PortalLogListItemResponse from(PortalLogEntry e) {
        return new PortalLogListItemResponse(e.getId(), e.getType(), e.getStartedAt(), e.getCompletedAt(),
                e.getUserName(), e.getScreen(), e.getAction(), e.getContent(), e.getResult(), e.getDescription());
    }
}

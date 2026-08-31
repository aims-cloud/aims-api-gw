package com.innogrid.aims.monitoring.dto.log;

import java.time.OffsetDateTime;

/** 감사 로그 상세 — 하단 상세 패널과 로그 관리 상세 페이지에 공용. */
public record AuditLogDetailResponse(
        Long logId,
        String screen,
        String userName,                // 예: 관리자
        String userId,                  // 예: superadmin — 화면에는 "관리자(superadmin)" 로 조합 표시
        String clientIp,                // 접속 IP
        String action,
        String content,
        AuditResult result,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        String description
) {
}

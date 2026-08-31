package com.innogrid.aims.monitoring.dto.log;

import java.time.OffsetDateTime;

/** 감사 로그 목록 1행 — 생성/완료 일시, 사용자, 화면, 작업, 내용, 결과, 설명. */
public record AuditLogListItemResponse(
        Long logId,
        OffsetDateTime startedAt,       // 생성 일시
        OffsetDateTime completedAt,     // 완료 일시
        String userName,                // 사용자 (예: 관리자)
        String screen,                  // 화면 (없으면 null — "-" 표시)
        String action,                  // 작업 (예: 사용자)
        String content,                 // 내용 (예: 로그인)
        AuditResult result,
        String description
) {
}

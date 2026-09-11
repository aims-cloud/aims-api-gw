package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

import java.time.OffsetDateTime;
import java.util.List;

/** 알람 정책 목록 1행. 대상 자원 수 클릭 시 GET /policies/{policyId}/resources 로 목록을 조회한다. */
public record AlarmPolicyListItemResponse(
        Long policyId,
        String policyName,
        String roleName,
        String areaId,
        String resourceType,
        long targetResourceCount,       // 대상 자원 수
        String criterion,               // 기준 (예: Memory Used > 80%)
        AlarmLevel level,
        List<AlarmChannel> channels,    // 알림 방식 (EMAIL|SMS) — 목록 열 표시용 (docs/10 §7 #4)
        String createdBy,
        OffsetDateTime createdAt
) {
}

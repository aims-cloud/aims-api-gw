package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

import java.time.OffsetDateTime;

/** 알람 정책 목록 1행. */
public record AlarmPolicyListItemResponse(
        Long policyId,
        String policyName,
        String roleName,
        String deploymentId,
        String resourceType,
        String criterion,               // 기준 (예: Memory Used > 80%)
        AlarmLevel level,
        String createdBy,
        OffsetDateTime createdAt
) {
}

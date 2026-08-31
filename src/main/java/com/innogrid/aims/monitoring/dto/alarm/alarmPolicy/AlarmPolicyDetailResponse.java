package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

import java.time.OffsetDateTime;
import java.util.List;

/** 알람 정책 상세 — 하단 상세 탭과 상세 페이지 상단 표에 공용. */
public record AlarmPolicyDetailResponse(
        Long policyId,
        AlarmPolicyType alarmType,
        String policyName,
        String roleId,
        String roleName,
        String deploymentId,
        String resourceType,
        AlarmTargetScope targetScope,
        String criterion,               // 기준 (예: CPU Used > 80%)
        AlarmLevel level,
        List<AlarmChannel> channels,    // 알림 방식 (예: Email + SMS)
        int durationSeconds,            // 조건 지속 시간 (즉시=0)
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String description
) {
    public AlarmPolicyDetailResponse {
        channels = channels == null ? List.of() : List.copyOf(channels);
    }
}

package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

/** 정책의 전송 대상 탭 1행 — 이름과 유형(조직/개인). */
public record AlarmPolicyRecipientResponse(
        AlarmTargetType targetType,
        String targetId,
        String name
) {
}

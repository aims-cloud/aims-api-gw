package com.innogrid.aims.monitoring.dto.alarm.alarmHistory;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;

import java.time.OffsetDateTime;

/** 알람 확인 내역 모달 — 발생 정보와 확인 일시/확인자/확인 내용. */
public record AlarmAcknowledgementResponse(
        OffsetDateTime occurredAt,
        AlarmLevel level,
        String resourceType,
        String resourceName,
        String criterion,
        String observedValue,
        OffsetDateTime acknowledgedAt,
        String acknowledgedBy,          // 확인자 (인증 사용자, 예: superadmin)
        String content
) {
}

package com.innogrid.aims.monitoring.dto.alarm.alarmHistory;

/** 알람 확인 요청 — 확인 내용은 필수이며, 확인자·확인 시각은 서버가 저장한다. */
public record AlarmAcknowledgeRequest(
        String content
) {
}

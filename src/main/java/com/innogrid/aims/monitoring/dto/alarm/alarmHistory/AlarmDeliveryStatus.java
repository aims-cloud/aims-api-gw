package com.innogrid.aims.monitoring.dto.alarm.alarmHistory;

/** 알림 전송 상태 — 전송 이력·메시지 상세의 결과 배지에 사용한다. */
public enum AlarmDeliveryStatus {
    SUCCESS,    // 성공
    SENDING,    // 전송중
    FAILED      // 실패
}

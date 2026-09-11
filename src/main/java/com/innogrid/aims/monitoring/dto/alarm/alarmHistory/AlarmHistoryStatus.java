package com.innogrid.aims.monitoring.dto.alarm.alarmHistory;

/**
 * 알람 이력 행의 상태 (docs/09 C.1) — 초안 어휘 OPEN → ACKNOWLEDGED → RESOLVED 를 그대로 잇는다.
 * 평가기 내부 상태(NORMAL/PENDING/FIRING)는 alarm_states 에만 두고 이력에는 노출하지 않는다.
 */
public enum AlarmHistoryStatus {
    /** 발생 후 미확인 */
    OPEN,
    /** 사용자가 확인 내용을 남김 */
    ACKNOWLEDGED,
    /** 조건이 해소됨(확인 여부와 무관) */
    RESOLVED
}

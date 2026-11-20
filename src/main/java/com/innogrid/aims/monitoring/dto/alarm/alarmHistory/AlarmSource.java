package com.innogrid.aims.monitoring.dto.alarm.alarmHistory;

/**
 * 알람 원천 (docs/09 B.4.2) — 이 알람이 어디서 만들어졌는가.
 */
public enum AlarmSource {
    /** 사용자 정책 평가기 */
    POLICY,
    /** 내장 시스템 규칙(에이전트 다운·사이트 생존성·데드맨·연동 장애) */
    SYSTEM,
    /** Alertmanager v2 호환 수신(세부1·세부3·KETI) */
    EXTERNAL,
    /** 장치 이벤트(Redfish EventService 등) */
    EVENT
}

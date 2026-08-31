package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

/** 알람 종류 — 정책 생성 위저드 1단계 카드 선택. */
public enum AlarmPolicyType {
    METRIC,     // 메트릭 알람 — CPU·Memory·File System 등 수치 지표 임계값
    LOG         // 로그 알람 — 로그 레벨·키워드 조합
}

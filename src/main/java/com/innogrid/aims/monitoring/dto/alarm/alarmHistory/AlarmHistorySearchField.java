package com.innogrid.aims.monitoring.dto.alarm.alarmHistory;

/**
 * 발생 이력 검색 대상 — 프론트 검색 대상 Select(전체 / 대상 자원 / 정책명)와 1:1.
 * ALL 은 자원 이름 + 정책명 두 컬럼이다(프론트 "전체"와 동일 범위).
 */
public enum AlarmHistorySearchField {
    ALL,
    RESOURCE_NAME,
    POLICY_NAME
}

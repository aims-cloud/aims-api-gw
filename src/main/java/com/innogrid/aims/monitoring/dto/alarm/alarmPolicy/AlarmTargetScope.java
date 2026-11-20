package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

/** 알람 정책의 대상 범위 — 전체 또는 개별 자원 선택. */
public enum AlarmTargetScope {
    ALL,            // 전체
    INDIVIDUAL      // 개별 (대상 자원 다중 선택)
}

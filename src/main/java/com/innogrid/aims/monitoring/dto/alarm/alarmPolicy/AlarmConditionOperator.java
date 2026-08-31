package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

/** 알람 조건 연산자 — 임계값과 조합해 기준(예: CPU Used > 80%)을 구성한다. */
public enum AlarmConditionOperator {
    GT, GTE, LT, LTE, EQ
}

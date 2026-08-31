package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

/** 정책의 대상 자원 탭 1행 — 이름(자원 상세 링크)/별칭/ID. */
public record AlarmPolicyResourceResponse(
        String resourceId,
        String name,
        String alias
) {
}

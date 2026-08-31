package com.innogrid.aims.monitoring.dto.alarm.alarmHistory;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;

import java.time.OffsetDateTime;

/**
 * 알람 발생 이력 목록 1행.
 * acknowledged=true 면 '확인 완료'(확인 내역 모달), false 면 '미확인'(알람 확인 모달) 버튼이 표시된다.
 */
public record AlarmHistoryListItemResponse(
        Long alarmId,
        OffsetDateTime occurredAt,
        AlarmLevel level,
        String resourceType,            // 대상 자원 유형 (노드 등) — TODO:: 값 체계 확정 후 enum 전환
        String resourceId,
        String resourceName,            // 화면에서 자원 상세로 링크
        String criterion,               // 기준 (예: CPU Used > 80%)
        String observedValue,           // 관측값 (예: 92%)
        String roleName,
        String deploymentId,
        Long policyId,
        String policyName,              // 화면에서 정책 상세로 링크
        long deliveryCount,             // 알림 전송 이력 수 — 클릭 시 행 확장(전송 이력 조회)
        boolean acknowledged
) {
}

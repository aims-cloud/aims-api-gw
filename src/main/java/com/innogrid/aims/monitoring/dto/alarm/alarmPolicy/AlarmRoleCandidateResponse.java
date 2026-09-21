package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

/**
 * 정책 생성 위저드·이력/정책 필터의 역할 드롭다운 옵션 1건.
 * roleName 은 정책·필터에 보내는 값(토큰 역할 이름), name 은 표시명(roles.description, 없으면 roleName).
 */
public record AlarmRoleCandidateResponse(
        String roleName,
        String name,
        long areaCount                  // 이 역할에서 고를 수 있는 배포영역(MDC + Namespace) 수
) {
}

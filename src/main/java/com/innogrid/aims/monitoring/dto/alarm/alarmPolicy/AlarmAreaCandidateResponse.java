package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;

/**
 * 정책 생성 위저드·이력/정책 필터의 배포영역(자원 영역) 드롭다운 옵션 1건.
 * areaId 는 정책·필터에 보내는 값 — MDC 는 mdcId, Tumblebug Namespace 는 {@code TB:{nsId}}(정책 원장·이력의 areaId 와 같은 체계).
 */
public record AlarmAreaCandidateResponse(
        String roleName,                // 이 영역이 속한 역할 — roleName 없이 전체 조회했을 때 역할별로 묶는 데 쓴다
        String areaId,
        AreaKind areaKind,              // MDC | NAMESPACE
        String name                     // 표시명 — MDC 는 mdcId, Namespace 는 Tumblebug 표시명
) {
}

package com.innogrid.aims.monitoring.dto.alarm.alarmHistory;

import java.time.OffsetDateTime;
import java.util.Locale;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.entity.AlarmHistory;

/**
 * 알람 발생 이력 목록 1행.
 * acknowledged=true 면 '확인 완료'(확인 내역 모달), false 면 '미확인'(알람 확인 모달) 버튼이 표시된다.
 * source/status/inhibited 는 docs/09 재설계에서 추가된 필드 — 외부·시스템 알람 구분과 억제 표시용.
 */
public record AlarmHistoryListItemResponse(
        Long alarmId,
        OffsetDateTime occurredAt,
        AlarmLevel level,
        AlarmSource source,
        AlarmHistoryStatus status,
        String resourceType,            // 대상 자원 유형 — node | vm | gpu
        String resourceId,
        String resourceName,            // 화면에서 자원 상세로 링크
        String criterion,               // 기준 (예: CPU Used > 80%)
        String observedValue,           // 관측값 표기 (예: 92%, 3.2 GB) — 값+단위, 없으면 null
        String roleName,
        String areaId,                  // 자원 영역 — MDC: mdcId, Namespace: TB:{nsId} (역할 패널 areaId 와 동일)
        AreaKind areaKind,              // MDC | NAMESPACE
        String areaName,                // 표시명 — MDC 는 mdcId, Namespace 는 Tumblebug 표시명(없으면 nsId)
        Long policyId,
        String policyName,              // 화면에서 정책 상세로 링크
        long deliveryCount,             // 알림 전송 이력 수 — 클릭 시 행 확장(전송 이력 조회)
        boolean acknowledged,
        boolean inhibited               // 상위 알람에 눌려 전송이 억제된 행
) {
    public static AlarmHistoryListItemResponse from(AlarmHistory e, String areaName) {
        return new AlarmHistoryListItemResponse(e.getId(), e.getOccurredAt(), e.getLevel(), e.getSource(), e.getStatus(),
                e.getResourceType(), e.getResourceId(), e.getResourceName(), e.getCriterion(),
                formatObserved(e.getObservedValue(), e.getUnit()), e.getRoleName(), e.getAreaId(), e.getAreaKind(), areaName,
                e.getPolicyId(), e.getPolicyName(), e.getDeliveryCount(), e.isAcknowledged(), e.isInhibited());
    }

    /** 92.0 + "%" → "92%", 3.25 + "GB" → "3.3 GB". 정수면 소수점 없이, 아니면 소수 1자리. %·°C 는 붙여 쓴다 */
    public static String formatObserved(Double value, String unit) {
        if (value == null) {
            return null;
        }
        String number = value == Math.rint(value)
                ? String.valueOf(value.longValue())
                : String.format(Locale.ROOT, "%.1f", value);
        if (unit == null || unit.isBlank()) {
            return number;
        }
        boolean attached = unit.equals("%") || unit.startsWith("°");
        return attached ? number + unit : number + " " + unit;
    }
}

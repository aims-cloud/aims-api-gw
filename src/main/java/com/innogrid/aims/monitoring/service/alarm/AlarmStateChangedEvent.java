package com.innogrid.aims.monitoring.service.alarm;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmSource;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse.AreaKind;
import com.innogrid.aims.monitoring.entity.AlarmHistory;

/**
 * 알람 상태 전이 이벤트 (docs/09 B.4.6) — 평가기가 FIRING(발생)·RESOLVED(해소) 전이 시 발행하는 Spring ApplicationEvent.
 *
 * 구독자는 평가 트랜잭션과 분리해 동작한다({@code @TransactionalEventListener(AFTER_COMMIT)}):
 * <ul>
 *   <li>{@link AlarmEventRecorder} — 대외 이벤트 원장({@code events})에 한 줄 적재</li>
 *   <li>(예정) 전송 파이프라인 — 수신자 전개·발송 작업 생성</li>
 *   <li>(예정) 스케줄러 — 온도 2단계 조치(허용 초과 → 배치 차단·이전 제안)</li>
 * </ul>
 * 값은 전이 시점의 이력 스냅샷이라 구독자가 원장을 다시 읽지 않아도 된다.
 */
public record AlarmStateChangedEvent(
        Transition transition,
        Long alarmHistoryId,
        AlarmSource source,
        Long policyId,
        String policyName,
        AlarmLevel level,
        String resourceType,
        String resourceId,
        String resourceName,
        String roleName,
        String areaId,
        AreaKind areaKind,
        String metric,
        String unit,
        String criterion,
        double observedValue,
        OffsetDateTime occurredAt
) {
    /** 전이 종류 — 발생 / 해소 */
    public enum Transition { FIRING, RESOLVED }

    /** 발생 — 새로 만든 이력 행과 관측값으로 */
    public static AlarmStateChangedEvent firing(AlarmHistory alarm, double observedValue) {
        return of(Transition.FIRING, alarm, observedValue, alarm.getOccurredAt());
    }

    /** 해소 — RESOLVED 로 닫은 이력 행과 그 시점 관측값으로 */
    public static AlarmStateChangedEvent resolved(AlarmHistory alarm, double observedValue) {
        return of(Transition.RESOLVED, alarm, observedValue, alarm.getResolvedAt());
    }

    private static AlarmStateChangedEvent of(Transition transition, AlarmHistory a, double value, OffsetDateTime at) {
        return new AlarmStateChangedEvent(transition, a.getId(), a.getSource(), a.getPolicyId(), a.getPolicyName(), a.getLevel(),
                a.getResourceType(), a.getResourceId(), a.getResourceName(), a.getRoleName(), a.getAreaId(), a.getAreaKind(),
                a.getMetric(), a.getUnit(), a.getCriterion(), value, at == null ? OffsetDateTime.now() : at);
    }

    /** MDC id — 영역이 MDC 일 때만. Namespace 영역이면 null */
    public String mdcId() {
        return areaKind == AreaKind.MDC ? areaId : null;
    }

    public boolean isFiring() {
        return transition == Transition.FIRING;
    }
}

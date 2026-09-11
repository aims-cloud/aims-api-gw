package com.innogrid.aims.monitoring.service.alarm;

import com.innogrid.aims.monitoring.entity.Event;
import com.innogrid.aims.monitoring.log.PortalLogService;
import com.innogrid.aims.monitoring.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 알람 전이 → 대외 이벤트 원장({@code events}) 적재 (docs/09 C.1 "Event 원장 = 대외 이벤트 발행 원장")
 * + 포털 로그 {@code ALARM} 요약 행 (docs/08 §3.2 — 발생·해소 각 1행, ref = ALARM_HISTORY).
 *
 * 평가 트랜잭션이 커밋된 뒤에만 동작하므로 평가가 롤백되면 이벤트·포털 로그도 남지 않고, 적재 실패가 평가를 되돌리지 않는다.
 * 트랜잭션 밖에서 발행된 이벤트(테스트·수동 호출)도 처리한다({@code fallbackExecution}).
 * 발생은 정책 레벨, 해소는 INFO 로 기록한다. 두 기록은 서로 독립이라 하나가 실패해도 다른 하나는 남는다.
 */
@Component
public class AlarmEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(AlarmEventRecorder.class);

    private final EventRepository eventRepository;
    private final PortalLogService portalLogService;

    public AlarmEventRecorder(EventRepository eventRepository, PortalLogService portalLogService) {
        this.eventRepository = eventRepository;
        this.portalLogService = portalLogService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AlarmStateChangedEvent e) {
        try {
            String mdcId = e.mdcId() == null ? "unknown" : e.mdcId();
            String severity = e.isFiring() ? e.level().name() : "INFO";
            String message = e.isFiring()
                    ? String.format("%s: %s (관측값 %.1f%s)", e.policyName(), e.criterion(), e.observedValue(), unit(e))
                    : String.format("%s 해소: %s (관측값 %.1f%s)", e.policyName(), e.criterion(), e.observedValue(), unit(e));
            eventRepository.save(new Event(mdcId, severity, e.resourceName(), message));
        } catch (RuntimeException ex) {
            log.warn("알람 이벤트 적재 실패: transition={}, alarmHistoryId={}, error={}", e.transition(), e.alarmHistoryId(), ex.getMessage());
        }
        recordPortalLog(e);
    }

    /** 포털 로그 ALARM 행 — 예: "CPU Used > 80% 발생: node-01 (92.4%)". 저장 실패는 Writer 가 삼킨다 */
    private void recordPortalLog(AlarmStateChangedEvent e) {
        if (e.alarmHistoryId() == null) {
            log.warn("알람 포털 로그 생략: 이력 id 없음, transition={}", e.transition());
            return;
        }
        String action = e.isFiring() ? "발생" : "해소";
        String content = String.format("%s %s: %s (%.1f%s)", e.criterion(), action, e.resourceName(), e.observedValue(), unit(e));
        portalLogService.alarm(action, content, e.alarmHistoryId(), e.occurredAt());
    }

    private static String unit(AlarmStateChangedEvent e) {
        return e.unit() == null ? "" : e.unit();
    }
}

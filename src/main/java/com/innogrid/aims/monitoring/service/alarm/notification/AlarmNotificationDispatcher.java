package com.innogrid.aims.monitoring.service.alarm.notification;

import java.util.List;

import com.innogrid.aims.monitoring.AlarmProperties;
import com.innogrid.aims.monitoring.service.alarm.AlarmStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 알람 전이 → 전송 파이프라인 진입점 (docs/09 A.5.4, B.4.6) — 발생(FIRING) 전이만 전송한다(해소는 이력만, A.6 #4).
 *
 * 평가 트랜잭션 커밋 뒤에 동작하므로 평가가 롤백되면 전송도 없다. 계획({@link AlarmDeliveryPlanner}, 별도 트랜잭션)은
 * 이 스레드에서 바로 만들어 "전송중" 행이 즉시 보이게 하고, 실제 발송({@link AlarmDeliverySender})은 전용 스레드로 넘긴다.
 * 여기서 난 예외는 삼킨다 — 전송 장애가 평가기나 다른 구독자({@code AlarmEventRecorder})를 막지 않는다.
 */
@Component
public class AlarmNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlarmNotificationDispatcher.class);

    private final AlarmProperties properties;
    private final AlarmDeliveryPlanner planner;
    private final AlarmDeliverySender sender;

    public AlarmNotificationDispatcher(AlarmProperties properties, AlarmDeliveryPlanner planner, AlarmDeliverySender sender) {
        this.properties = properties;
        this.planner = planner;
        this.sender = sender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(AlarmStateChangedEvent e) {
        if (!properties.getNotification().isEnabled() || !e.isFiring() || e.policyId() == null) return;
        try {
            List<Long> pending = planner.plan(e);
            sender.submit(pending);
        } catch (RuntimeException ex) {
            log.warn("알람 전송 계획 실패: alarmHistoryId={}, policyId={}, error={}", e.alarmHistoryId(), e.policyId(), ex.getMessage());
        }
    }
}

package com.innogrid.aims.monitoring.service.alarm.notification;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;

/**
 * 채널별 발송기 (docs/09 A.5.4) — Email 은 Spring Mail, SMS 는 게이트웨이 확정 후 구현.
 * 구현체는 채널 하나를 맡고, {@link AlarmDeliverySender} 가 채널로 골라 호출한다.
 */
public interface NotificationSender {

    AlarmChannel channel();

    /**
     * 수신 주소 1건에 발송한다. 성공이면 정상 반환, 실패면 예외 — 예외 메시지가 전송 원장의 실패 사유가 된다.
     * 재시도는 호출 측({@link AlarmDeliverySender})이 맡으므로 구현체는 한 번만 시도한다.
     */
    void send(String address, String subject, String body) throws Exception;
}

package com.innogrid.aims.monitoring.service.alarm.notification;

import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import org.springframework.stereotype.Component;

/**
 * SMS 발송기 스텁 (docs/09 A.6 #3) — 게이트웨이(업체 API / 사내 SMPP)가 정해질 때까지 항상 실패 사유 "미구성"을 남긴다.
 * 현재는 {@code users.phone} 컬럼도 없어 전개 단계에서 "연락처 없음"으로 먼저 FAILED 되므로 이 스텁까지 오는 경우는 없다.
 */
@Component
public class SmsNotificationSender implements NotificationSender {

    @Override
    public AlarmChannel channel() {
        return AlarmChannel.SMS;
    }

    @Override
    public void send(String address, String subject, String body) {
        throw new IllegalStateException("SMS 게이트웨이 미구성");
    }
}

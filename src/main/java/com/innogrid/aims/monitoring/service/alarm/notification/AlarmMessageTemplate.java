package com.innogrid.aims.monitoring.service.alarm.notification;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.innogrid.aims.monitoring.AlarmProperties;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmHistoryListItemResponse;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.service.alarm.AlarmStateChangedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 알람 메시지 템플릿 (docs/09 A.5.4) — 정책 레벨·메트릭·자원명·관측값·시각을 치환한 결과를 전송 원장에 스냅샷으로 저장한다.
 * 관측값 표기는 이력 목록과 같은 규칙({@link AlarmHistoryListItemResponse#formatObserved}).
 */
@Component
public class AlarmMessageTemplate {

    private static final DateTimeFormatter EMAIL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");
    private static final DateTimeFormatter SMS_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final AlarmProperties properties;
    private final ZoneId zone;

    @Autowired
    public AlarmMessageTemplate(AlarmProperties properties) {
        this(properties, ZoneId.systemDefault());
    }

    AlarmMessageTemplate(AlarmProperties properties, ZoneId zone) {
        this.properties = properties;
        this.zone = zone;
    }

    /** 채널별 본문을 미리 만들어 둔 메시지 — 제목은 공통 */
    public record Message(String subject, String emailBody, String smsBody) {
        public String bodyFor(AlarmChannel channel) {
            return channel == AlarmChannel.SMS ? smsBody : emailBody;
        }
    }

    public Message render(AlarmStateChangedEvent e) {
        String observed = AlarmHistoryListItemResponse.formatObserved(e.observedValue(), e.unit());
        String prefix = prefix();
        String subject = String.format("%s[%s] %s — %s %s (%s)",
                prefix, e.level(), nvl(e.policyName(), "알람"), nvl(e.resourceName(), e.resourceId()), e.criterion(), observed);

        StringBuilder email = new StringBuilder();
        email.append("AIMS Cloud 알람이 발생했습니다.\n\n");
        email.append("정책: ").append(nvl(e.policyName(), "-")).append('\n');
        email.append("레벨: ").append(e.level()).append('\n');
        email.append("대상 자원: ").append(nvl(e.resourceName(), "-"));
        if (e.resourceType() != null) email.append(" (").append(e.resourceType()).append(')');
        email.append('\n');
        email.append("자원 영역: ").append(nvl(e.areaId(), "-")).append('\n');
        email.append("기준: ").append(e.criterion()).append('\n');
        email.append("관측값: ").append(observed).append('\n');
        email.append("발생 시각: ").append(e.occurredAt().atZoneSameInstant(zone).format(EMAIL_TIME)).append('\n');
        if (e.roleName() != null) email.append("역할: ").append(e.roleName()).append('\n');
        email.append('\n').append("포털 관측 > 알람 화면에서 확인 처리해 주세요.");

        String sms = String.format("%s[%s] %s %s %s (%s) %s",
                prefix, e.level(), nvl(e.policyName(), "알람"), nvl(e.resourceName(), e.resourceId()), e.criterion(), observed,
                e.occurredAt().atZoneSameInstant(zone).format(SMS_TIME));

        return new Message(subject, email.toString(), sms);
    }

    private String prefix() {
        String p = properties.getNotification().getSubjectPrefix();
        return p == null || p.isBlank() ? "" : p.trim();
    }

    private static String nvl(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}

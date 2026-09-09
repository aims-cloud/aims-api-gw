package com.innogrid.aims.monitoring.service.alarm.notification;

import com.innogrid.aims.monitoring.AlarmProperties;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Email 발송기 — Spring Mail({@code spring.mail.*}). 메일 서버는 운영 환경 변수({@code SPRING_MAIL_HOST} 등)로만 넣고
 * 저장소에는 두지 않는다. 로컬은 Mailpit 같은 가짜 SMTP({@code localhost:1025})로 실제 발송 없이 확인한다.
 *
 * {@code spring.mail.host} 가 없으면 Boot 가 {@link JavaMailSender} 빈을 만들지 않으므로 {@link ObjectProvider} 로 받고,
 * 없을 때는 예외로 "미구성" 사유를 남긴다(정책 저장·평가는 그대로 동작).
 */
@Component
public class EmailNotificationSender implements NotificationSender {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final AlarmProperties properties;

    public EmailNotificationSender(ObjectProvider<JavaMailSender> mailSender, AlarmProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public AlarmChannel channel() {
        return AlarmChannel.EMAIL;
    }

    @Override
    public void send(String address, String subject, String body) throws Exception {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("메일 서버 미구성 (SPRING_MAIL_HOST)");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        String from = properties.getNotification().getFrom();
        if (from != null && !from.isBlank()) {
            message.setFrom(from.trim());
        }
        message.setTo(address);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }
}

package com.innogrid.aims.monitoring.service.alarm.notification;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmDeliveryStatus;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetType;
import com.innogrid.aims.monitoring.entity.AlarmDelivery;
import com.innogrid.aims.monitoring.entity.AlarmDeliveryRecipient;
import com.innogrid.aims.monitoring.repository.AlarmDeliveryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 발송기 — SENDING 수신자에게만 채널 발송기로 보내고 결과를 기록한다. 성공은 SUCCESS + 시도 1회,
 * 계속 실패면 max-attempts(3) 만큼 재시도한 뒤 FAILED + 마지막 사유, 두 번째에 성공하면 attempts=2,
 * 한 수신자의 실패가 다른 수신자를 막지 않는다. 메일 서버는 대역(JavaMailSender mock).
 */
@SpringBootTest
class AlarmDeliverySenderTest {

    @Autowired AlarmDeliverySender sender;
    @Autowired AlarmDeliveryRepository deliveryRepository;
    @MockitoBean JavaMailSender mailSender;

    @AfterEach
    void cleanUp() {
        deliveryRepository.deleteAll();
    }

    private AlarmDelivery delivery(AlarmChannel channel, String... addresses) {
        AlarmDelivery d = new AlarmDelivery(1L, 7L, AlarmTargetType.ORGANIZATION, "플랫폼팀", "플랫폼팀", channel,
                "[AIMS][CRITICAL] cpu-high — node-01 CPU Used > 80% (92.4%)", "본문", OffsetDateTime.now());
        long id = 1;
        for (String a : addresses) d.addPending(id++, "사용자" + id, a);
        return deliveryRepository.save(d);
    }

    @Test
    void 성공하면_SUCCESS_와_시도_1회_수신자별로_메일을_보낸다() {
        AlarmDelivery d = delivery(AlarmChannel.EMAIL, "kim@innogrid.com", "park@innogrid.com");
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        sender.send(d.getId());

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(2)).send(captor.capture());
        assertThat(captor.getAllValues()).extracting(m -> m.getTo()[0]).containsExactly("kim@innogrid.com", "park@innogrid.com");
        assertThat(captor.getAllValues().get(0).getSubject()).isEqualTo("[AIMS][CRITICAL] cpu-high — node-01 CPU Used > 80% (92.4%)");
        assertThat(captor.getAllValues().get(0).getText()).isEqualTo("본문");

        List<AlarmDeliveryRecipient> recipients = deliveryRepository.findWithRecipientsById(d.getId()).orElseThrow().getRecipients();
        assertThat(recipients).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(AlarmDeliveryStatus.SUCCESS);
            assertThat(r.getAttempts()).isEqualTo(1);
            assertThat(r.getSentAt()).isNotNull();
            assertThat(r.getFailReason()).isNull();
        });
    }

    @Test
    void 계속_실패하면_세_번_시도한_뒤_FAILED_와_마지막_사유를_남기고_다른_수신자는_계속_보낸다() {
        AlarmDelivery d = delivery(AlarmChannel.EMAIL, "bad@innogrid.com", "ok@innogrid.com");
        doThrow(new MailSendException("550 mailbox unavailable")).when(mailSender)
                .send(org.mockito.ArgumentMatchers.<SimpleMailMessage>argThat(m -> m != null && m.getTo()[0].equals("bad@innogrid.com")));

        sender.send(d.getId());

        verify(mailSender, times(4)).send(any(SimpleMailMessage.class));          // bad 3회 + ok 1회
        List<AlarmDeliveryRecipient> recipients = deliveryRepository.findWithRecipientsById(d.getId()).orElseThrow().getRecipients();
        AlarmDeliveryRecipient bad = recipients.get(0);
        assertThat(bad.getStatus()).isEqualTo(AlarmDeliveryStatus.FAILED);
        assertThat(bad.getAttempts()).isEqualTo(3);
        assertThat(bad.getFailReason()).isEqualTo("MailSendException: 550 mailbox unavailable");
        assertThat(recipients.get(1).getStatus()).isEqualTo(AlarmDeliveryStatus.SUCCESS);
    }

    @Test
    void 두_번째_시도에_성공하면_attempts_는_2다() {
        AlarmDelivery d = delivery(AlarmChannel.EMAIL, "flaky@innogrid.com");
        doThrow(new MailSendException("timeout")).doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        sender.send(d.getId());

        AlarmDeliveryRecipient r = deliveryRepository.findWithRecipientsById(d.getId()).orElseThrow().getRecipients().get(0);
        assertThat(r.getStatus()).isEqualTo(AlarmDeliveryStatus.SUCCESS);
        assertThat(r.getAttempts()).isEqualTo(2);
    }

    @Test
    void SMS_는_게이트웨이_미구성_사유로_FAILED_이고_이미_결과가_있는_수신자는_건드리지_않는다() {
        AlarmDelivery d = new AlarmDelivery(1L, 7L, AlarmTargetType.INDIVIDUAL, "3", "김철수", AlarmChannel.SMS, "제목", "본문",
                OffsetDateTime.now());
        d.addPending(3L, "김철수", "010-0000-0000");
        d.addFailed(4L, "이영희", null, AlarmDeliveryPlanner.REASON_NO_ADDRESS, OffsetDateTime.now());
        d = deliveryRepository.save(d);

        sender.send(d.getId());

        List<AlarmDeliveryRecipient> recipients = deliveryRepository.findWithRecipientsById(d.getId()).orElseThrow().getRecipients();
        assertThat(recipients.get(0).getStatus()).isEqualTo(AlarmDeliveryStatus.FAILED);
        assertThat(recipients.get(0).getFailReason()).isEqualTo("IllegalStateException: SMS 게이트웨이 미구성");
        assertThat(recipients.get(0).getAttempts()).isEqualTo(3);
        assertThat(recipients.get(1).getFailReason()).isEqualTo(AlarmDeliveryPlanner.REASON_NO_ADDRESS);
        assertThat(recipients.get(1).getAttempts()).isZero();
    }
}

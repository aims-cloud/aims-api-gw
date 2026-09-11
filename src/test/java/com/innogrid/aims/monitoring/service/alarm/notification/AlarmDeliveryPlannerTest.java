package com.innogrid.aims.monitoring.service.alarm.notification;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.repository.UserRepository;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmDeliveryStatus;
import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmSource;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmConditionOperator;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmLevel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetScope;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetType;
import com.innogrid.aims.monitoring.entity.AlarmDelivery;
import com.innogrid.aims.monitoring.entity.AlarmDeliveryRecipient;
import com.innogrid.aims.monitoring.entity.AlarmHistory;
import com.innogrid.aims.monitoring.entity.AlarmPolicy;
import com.innogrid.aims.monitoring.entity.AlarmPolicy.RecipientRef;
import com.innogrid.aims.monitoring.repository.AlarmDeliveryRepository;
import com.innogrid.aims.monitoring.repository.AlarmHistoryRepository;
import com.innogrid.aims.monitoring.repository.AlarmPolicyRepository;
import com.innogrid.aims.monitoring.service.alarm.AlarmMetric;
import com.innogrid.aims.monitoring.service.alarm.AlarmStateChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전송 계획 — 정책 수신자(개인·조직)를 채널별로 전개해 전송 원장을 만든다. EMAIL 은 SENDING, SMS 는 연락처 없음 FAILED,
 * 삭제·비활성 사용자는 사유와 함께 FAILED, 여러 대상에 걸린 사용자는 채널당 한 번, delivery_count 는 전송 행 수만큼.
 * 이벤트 발행 → 커밋 뒤 디스패처가 계획을 만드는 배선도 확인한다. 원장은 실제 H2, 메일 서버는 대역.
 */
@SpringBootTest
class AlarmDeliveryPlannerTest {

    static final OffsetDateTime AT = OffsetDateTime.of(2026, 9, 9, 14, 3, 0, 0, ZoneOffset.ofHours(9));

    @Autowired AlarmDeliveryPlanner planner;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository userRepository;
    @Autowired AlarmPolicyRepository policyRepository;
    @Autowired AlarmHistoryRepository historyRepository;
    @Autowired AlarmDeliveryRepository deliveryRepository;
    @MockitoBean JavaMailSender mailSender;

    List<Long> usersBefore;
    Long kim;       // 플랫폼팀
    Long lee;       // 플랫폼팀, 비활성
    Long park;      // 무소속
    Long policyId;

    @BeforeEach
    void setUp() {
        usersBefore = userRepository.findAll().stream().map(User::getId).toList();
        kim = userRepository.save(new User("kim@innogrid.com", "pw", "김철수", "플랫폼팀")).getId();
        User inactive = new User("lee@innogrid.com", "pw", "이영희", "플랫폼팀");
        inactive.setStatus(User.UserStatus.INACTIVE);
        lee = userRepository.save(inactive).getId();
        park = userRepository.save(new User("park@innogrid.com", "pw", "박민수", null)).getId();
        policyId = policyRepository.save(new AlarmPolicy("cpu-high", "ADMIN", "mdc-01", "node", AlarmTargetScope.ALL, Set.of(),
                AlarmMetric.CPU_USED_PERCENT, AlarmConditionOperator.GT, 80, AlarmLevel.CRITICAL, 0,
                List.of(AlarmChannel.EMAIL, AlarmChannel.SMS),
                List.of(new RecipientRef(AlarmTargetType.INDIVIDUAL, String.valueOf(kim)),          // 개인
                        new RecipientRef(AlarmTargetType.ORGANIZATION, "플랫폼팀"),                  // 김철수(중복)·이영희(비활성)
                        new RecipientRef(AlarmTargetType.ORGANIZATION, "미지정"),                    // 박민수
                        new RecipientRef(AlarmTargetType.INDIVIDUAL, "999999")),                    // 삭제된 사용자
                "설명", "admin@innogrid.com")).getId();
    }

    @AfterEach
    void cleanUp() {
        deliveryRepository.deleteAll();
        historyRepository.deleteAll();
        policyRepository.deleteAll();
        userRepository.findAll().stream().filter(u -> !usersBefore.contains(u.getId())).forEach(userRepository::delete);
    }

    private AlarmHistory firingAlarm() {
        return historyRepository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.CRITICAL, "CPU Used > 80%")
                .policy(policyId, "cpu-high").resource("node", "1", "node-01").role("ADMIN").mdc("mdc-01")
                .metric("CPU_USED_PERCENT").observed(92.4, "%").occurredAt(AT).build());
    }

    @Test
    void 수신자를_채널별로_전개하고_EMAIL은_SENDING_SMS는_연락처_없음_중복과_삭제_비활성은_사유와_함께_FAILED() {
        AlarmHistory alarm = firingAlarm();

        List<Long> pending = planner.plan(AlarmStateChangedEvent.firing(alarm, 92.4));

        List<AlarmDelivery> deliveries = deliveryRepository.findByHistoryIdOrderByIdAsc(alarm.getId());
        assertThat(deliveries).hasSize(8);                                                     // 대상 4 × 채널 2
        assertThat(deliveries).allSatisfy(d -> {
            assertThat(d.getSubject()).isEqualTo("[AIMS][CRITICAL] cpu-high — node-01 CPU Used > 80% (92.4%)");
            assertThat(d.getPolicyId()).isEqualTo(policyId);
        });

        AlarmDelivery kimEmail = find(deliveries, AlarmTargetType.INDIVIDUAL, String.valueOf(kim), AlarmChannel.EMAIL);
        assertThat(kimEmail.getTargetName()).isEqualTo("김철수");
        assertThat(kimEmail.getBody()).contains("정책: cpu-high", "관측값: 92.4%", "발생 시각: 2026-09-09 14:03:00 +09:00");
        assertThat(kimEmail.getRecipients()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(AlarmDeliveryStatus.SENDING);
            assertThat(r.getAddress()).isEqualTo("kim@innogrid.com");
            assertThat(r.getUserId()).isEqualTo(kim);
        });
        AlarmDelivery kimSms = find(deliveries, AlarmTargetType.INDIVIDUAL, String.valueOf(kim), AlarmChannel.SMS);
        assertThat(kimSms.getBody()).startsWith("[AIMS][CRITICAL] cpu-high node-01 CPU Used > 80% (92.4%)");
        assertThat(kimSms.getRecipients()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(AlarmDeliveryStatus.FAILED);
            assertThat(r.getFailReason()).isEqualTo(AlarmDeliveryPlanner.REASON_NO_ADDRESS);
            assertThat(r.getAttempts()).isZero();
        });

        AlarmDelivery orgEmail = find(deliveries, AlarmTargetType.ORGANIZATION, "플랫폼팀", AlarmChannel.EMAIL);
        assertThat(orgEmail.getTargetName()).isEqualTo("플랫폼팀");
        assertThat(orgEmail.getRecipients()).extracting(AlarmDeliveryRecipient::getUserId).containsExactly(lee);   // 김철수는 개인으로 이미
        assertThat(orgEmail.getRecipients().get(0).getStatus()).isEqualTo(AlarmDeliveryStatus.FAILED);
        assertThat(orgEmail.getRecipients().get(0).getFailReason()).isEqualTo(AlarmDeliveryPlanner.REASON_INACTIVE);

        AlarmDelivery unassignedEmail = find(deliveries, AlarmTargetType.ORGANIZATION, "미지정", AlarmChannel.EMAIL);
        assertThat(unassignedEmail.getRecipients()).extracting(AlarmDeliveryRecipient::getUserId).contains(park);
        assertThat(unassignedEmail.getRecipients()).filteredOn(r -> park.equals(r.getUserId()))
                .singleElement().extracting(AlarmDeliveryRecipient::getStatus).isEqualTo(AlarmDeliveryStatus.SENDING);

        AlarmDelivery deletedEmail = find(deliveries, AlarmTargetType.INDIVIDUAL, "999999", AlarmChannel.EMAIL);
        assertThat(deletedEmail.getTargetName()).isEqualTo(AlarmDeliveryPlanner.DELETED_USER);
        assertThat(deletedEmail.getRecipients()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(AlarmDeliveryStatus.FAILED);
            assertThat(r.getFailReason()).isEqualTo(AlarmDeliveryPlanner.REASON_DELETED);
            assertThat(r.getUserId()).isNull();
        });

        assertThat(pending).containsExactlyInAnyOrder(kimEmail.getId(), unassignedEmail.getId());   // SENDING 이 있는 전송만
        assertThat(historyRepository.findById(alarm.getId()).orElseThrow().getDeliveryCount()).isEqualTo(8);
    }

    @Test
    void 해소_전이와_정책_없는_알람은_전송을_만들지_않는다() {
        AlarmHistory alarm = firingAlarm();
        alarm.resolve(AT.plusMinutes(5));
        alarm = historyRepository.save(alarm);
        assertThat(planner.plan(AlarmStateChangedEvent.resolved(alarm, 40.0))).isEmpty();

        AlarmHistory orphan = historyRepository.save(AlarmHistory.builder(AlarmSource.POLICY, AlarmLevel.WARNING, "x > 1")
                .policy(424242L, "gone").resource("node", "1", "node-01").occurredAt(AT).build());
        assertThat(planner.plan(AlarmStateChangedEvent.firing(orphan, 2.0))).isEmpty();

        assertThat(deliveryRepository.findAll()).isEmpty();
    }

    @Test
    void 평가_트랜잭션이_커밋되면_디스패처가_전송_원장을_만들고_롤백이면_만들지_않는다() {
        AlarmHistory committed = firingAlarm();
        tx.executeWithoutResult(s -> publisher.publishEvent(AlarmStateChangedEvent.firing(committed, 92.4)));
        assertThat(deliveryRepository.findByHistoryIdOrderByIdAsc(committed.getId())).hasSize(8);

        AlarmHistory rolledBack = firingAlarm();
        tx.executeWithoutResult(s -> {
            publisher.publishEvent(AlarmStateChangedEvent.firing(rolledBack, 92.4));
            s.setRollbackOnly();
        });
        assertThat(deliveryRepository.findByHistoryIdOrderByIdAsc(rolledBack.getId())).isEmpty();
    }

    private static AlarmDelivery find(List<AlarmDelivery> all, AlarmTargetType type, String targetId, AlarmChannel channel) {
        return all.stream().filter(d -> d.getTargetType() == type && d.getTargetId().equals(targetId) && d.getChannel() == channel)
                .findFirst().orElseThrow(() -> new AssertionError("전송 없음: " + type + " " + targetId + " " + channel));
    }
}

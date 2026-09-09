package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmDeliveryStatus;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmChannel;
import com.innogrid.aims.monitoring.dto.alarm.alarmPolicy.AlarmTargetType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * 알람 전송 원장 (docs/09 A.5.3 {@code alarm_deliveries}) — 알람 1건 × 전송 대상(개인/조직) 1건 × 채널 1개가 한 행.
 *
 * <ul>
 *   <li>제목·본문은 발송 시점에 치환한 결과의 <b>스냅샷</b>이다(메시지 상세 모달이 그대로 보여 준다).</li>
 *   <li>대상을 전개한 수신자(사용자)별 결과는 {@link AlarmDeliveryRecipient} 로 둔다 — 개인 대상은 1행, 조직 대상은 구성원 수만큼.</li>
 *   <li>{@code history_id}·{@code policy_id} 는 조인용 약한 참조(FK 없음) — 이력·정책이 지워져도 전송 기록은 남는다.</li>
 * </ul>
 */
@Entity
@Table(name = "alarm_deliveries", indexes = @Index(name = "idx_alarm_deliveries_history", columnList = "history_id"))
public class AlarmDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "history_id", nullable = false)
    private Long historyId;

    @Column(name = "policy_id")
    private Long policyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private AlarmTargetType targetType;

    @Column(name = "target_id", nullable = false, length = 100)
    private String targetId;

    @Column(name = "target_name", length = 100)
    private String targetName;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private AlarmChannel channel;

    @Column(name = "subject", length = 300)
    private String subject;

    @Column(name = "body", length = 4000)
    private String body;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "delivery", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<AlarmDeliveryRecipient> recipients = new ArrayList<>();

    protected AlarmDelivery() {}

    public AlarmDelivery(Long historyId, Long policyId, AlarmTargetType targetType, String targetId, String targetName,
                         AlarmChannel channel, String subject, String body, OffsetDateTime createdAt) {
        this.historyId = historyId;
        this.policyId = policyId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetName = targetName;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.createdAt = createdAt;
    }

    /** 발송 대기 수신자 — SENDING 으로 시작하고 발송기가 결과로 갱신한다 */
    public AlarmDeliveryRecipient addPending(Long userId, String name, String address) {
        AlarmDeliveryRecipient r = new AlarmDeliveryRecipient(this, userId, name, address, AlarmDeliveryStatus.SENDING);
        recipients.add(r);
        return r;
    }

    /** 발송 시도조차 못 하는 수신자(연락처 없음·삭제된 사용자 등) — 사유와 함께 FAILED 로 바로 기록 */
    public AlarmDeliveryRecipient addFailed(Long userId, String name, String address, String reason, OffsetDateTime at) {
        AlarmDeliveryRecipient r = new AlarmDeliveryRecipient(this, userId, name, address, AlarmDeliveryStatus.FAILED);
        r.markFailed(0, reason, at);
        recipients.add(r);
        return r;
    }

    public long countByStatus(AlarmDeliveryStatus status) {
        return recipients.stream().filter(r -> r.getStatus() == status).count();
    }

    public boolean hasPending() {
        return recipients.stream().anyMatch(r -> r.getStatus() == AlarmDeliveryStatus.SENDING);
    }

    public Long getId() { return id; }
    public Long getHistoryId() { return historyId; }
    public Long getPolicyId() { return policyId; }
    public AlarmTargetType getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getTargetName() { return targetName; }
    public AlarmChannel getChannel() { return channel; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public List<AlarmDeliveryRecipient> getRecipients() { return recipients; }
}

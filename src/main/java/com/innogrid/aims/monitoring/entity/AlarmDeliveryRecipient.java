package com.innogrid.aims.monitoring.entity;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.dto.alarm.alarmHistory.AlarmDeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 전송 수신자 1행 (docs/09 A.5.3 {@code alarm_delivery_recipients}) — 전송 대상을 사용자 단위로 전개한 결과와 채널별 발송 결과.
 * 수신 주소는 채널에 따라 이메일 또는 전화번호. 이름·주소는 발송 시점 스냅샷이다(사용자가 나중에 바뀌어도 기록은 불변).
 */
@Entity
@Table(name = "alarm_delivery_recipients",
        indexes = @Index(name = "idx_alarm_delivery_recipients_delivery", columnList = "delivery_id"))
public class AlarmDeliveryRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private AlarmDelivery delivery;

    /** users.id — 삭제된 사용자면 null 일 수 있다 */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "address", length = 200)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AlarmDeliveryStatus status;

    /** 발송 시도 횟수 — 재시도 포함. 시도 전 FAILED(연락처 없음 등)는 0 */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** 마지막 시도 시각(성공·실패 모두). 미시도면 null */
    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "fail_reason", length = 500)
    private String failReason;

    protected AlarmDeliveryRecipient() {}

    AlarmDeliveryRecipient(AlarmDelivery delivery, Long userId, String name, String address, AlarmDeliveryStatus status) {
        this.delivery = delivery;
        this.userId = userId;
        this.name = name;
        this.address = address;
        this.status = status;
    }

    public void markSuccess(int attempts, OffsetDateTime at) {
        this.status = AlarmDeliveryStatus.SUCCESS;
        this.attempts = attempts;
        this.sentAt = at;
        this.failReason = null;
    }

    public void markFailed(int attempts, String reason, OffsetDateTime at) {
        this.status = AlarmDeliveryStatus.FAILED;
        this.attempts = attempts;
        this.sentAt = at;
        this.failReason = reason == null ? null : (reason.length() > 500 ? reason.substring(0, 500) : reason);
    }

    public Long getId() { return id; }
    public AlarmDelivery getDelivery() { return delivery; }
    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public AlarmDeliveryStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public OffsetDateTime getSentAt() { return sentAt; }
    public String getFailReason() { return failReason; }
}

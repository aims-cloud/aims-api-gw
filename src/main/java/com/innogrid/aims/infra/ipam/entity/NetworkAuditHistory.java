package com.innogrid.aims.infra.ipam.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 네트워크 IPAM 공용 행위이력 (네트워크 구역/서브넷/IP 주소).
 * 화면 컬럼: 사용자 / 접속 IP / 작업(대상) / 내용 / 결과 / 설명 / 시작·완료 일시.
 */
@Entity
@Table(name = "network_audit_histories")
public class NetworkAuditHistory {

    /** 작업 대상 (화면 '작업' 컬럼). */
    public enum Target { ZONE, SUBNET, IP, INTERFACE }

    /** 내용 (화면 '내용' 컬럼). */
    public enum Action { CREATE, UPDATE, DELETE }

    public enum Result { SUCCESS, FAILURE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Target target;

    /** 대상 엔티티 id (구역/서브넷/IP의 id) — 상세 화면 이력 필터용. */
    @Column(name = "target_id")
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Result result;

    @Column(length = 1000)
    private String detail;

    @Column(length = 100)
    private String performedBy;

    @Column(length = 60)
    private String clientIp;

    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;

    protected NetworkAuditHistory() {}

    public NetworkAuditHistory(Target target, Long targetId, Action action, Result result,
                               String detail, String performedBy, String clientIp,
                               OffsetDateTime startedAt) {
        this.target = target;
        this.targetId = targetId;
        this.action = action;
        this.result = result;
        this.detail = detail;
        this.performedBy = performedBy;
        this.clientIp = clientIp;
        this.startedAt = startedAt;
        this.completedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Target getTarget() { return target; }
    public Long getTargetId() { return targetId; }
    public Action getAction() { return action; }
    public Result getResult() { return result; }
    public String getDetail() { return detail; }
    public String getPerformedBy() { return performedBy; }
    public String getClientIp() { return clientIp; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}

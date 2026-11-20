package com.innogrid.aims.resource.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 가상서버 그룹 행위이력 (감사 로그).
 * 화면 컬럼: 사용자 / 접속 IP / 작업(대상) / 내용 / 결과 / 설명 / 시작·완료 일시.
 */
@Entity
@Table(name = "vm_group_action_histories")
public class VmGroupActionHistory {

    /** 작업 대상 구분 (화면 '작업' 컬럼: 가상서버 그룹 / 가상서버). */
    public enum Target { GROUP, SERVER, CONNECTION }

    /** 내용 (화면 '내용' 컬럼). */
    public enum Action { CREATE, UPDATE, DELETE, SERVER_ADD, SERVER_REMOVE, CONNECTION_UPDATE }

    public enum Result { SUCCESS, PARTIAL, FAILURE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long vmGroupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Target target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Result result;

    /** 설명 (화면 '설명' 컬럼). */
    @Column(length = 1000)
    private String detail;

    /** 수행자 (인증 사용자 이름). */
    @Column(length = 100)
    private String performedBy;

    /** 접속 IP. */
    @Column(length = 60)
    private String clientIp;

    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;

    protected VmGroupActionHistory() {}

    public VmGroupActionHistory(Long vmGroupId, Target target, Action action, Result result,
                                String detail, String performedBy, String clientIp,
                                OffsetDateTime startedAt) {
        this.vmGroupId = vmGroupId;
        this.target = target;
        this.action = action;
        this.result = result;
        this.detail = detail;
        this.performedBy = performedBy;
        this.clientIp = clientIp;
        this.startedAt = startedAt;
        this.completedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getVmGroupId() { return vmGroupId; }
    public Target getTarget() { return target; }
    public Action getAction() { return action; }
    public Result getResult() { return result; }
    public String getDetail() { return detail; }
    public String getPerformedBy() { return performedBy; }
    public String getClientIp() { return clientIp; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}

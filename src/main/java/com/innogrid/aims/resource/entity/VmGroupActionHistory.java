package com.innogrid.aims.resource.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 가상서버 그룹 행위이력.
 * 그룹 생성/수정/삭제 및 일괄 동작(시작/중지)의 수행 결과를 기록한다.
 */
@Entity
@Table(name = "vm_group_action_histories")
public class VmGroupActionHistory {

    public enum Action { CREATE, UPDATE, DELETE, START, STOP }

    public enum Result { SUCCESS, PARTIAL, FAILURE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long vmGroupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Result result;

    /** 수행 상세 (예: "3/3 VM resume 성공", 실패 VM 목록 등) */
    @Column(length = 1000)
    private String detail;

    /** 수행자 (인증 사용자 이메일) */
    @Column(length = 100)
    private String performedBy;

    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected VmGroupActionHistory() {}

    public VmGroupActionHistory(Long vmGroupId, Action action, Result result,
                                String detail, String performedBy) {
        this.vmGroupId = vmGroupId;
        this.action = action;
        this.result = result;
        this.detail = detail;
        this.performedBy = performedBy;
    }

    public Long getId() { return id; }
    public Long getVmGroupId() { return vmGroupId; }
    public Action getAction() { return action; }
    public Result getResult() { return result; }
    public String getDetail() { return detail; }
    public String getPerformedBy() { return performedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

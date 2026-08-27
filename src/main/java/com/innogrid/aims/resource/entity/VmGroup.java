package com.innogrid.aims.resource.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;

/**
 * 가상서버 그룹.
 * 여러 클라우드(CB-Spider 연결)에 흩어진 VM을 논리 그룹으로 묶어
 * 일괄 동작(시작/중지)과 그룹 단위 조회를 제공한다.
 */
@Entity
@Table(name = "vm_groups")
public class VmGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @Column(length = 500)
    private String description;

    /** 배포영역 (MDC 식별자 또는 퍼블릭 클라우드 리전 표기) */
    @Column(length = 100)
    private String deployZone;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "vm_group_members", joinColumns = @JoinColumn(name = "vm_group_id"))
    private List<VmGroupMember> members = new ArrayList<>();

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }

    protected VmGroup() {}

    public VmGroup(String name, String description, String deployZone) {
        this.name = name;
        this.description = description;
        this.deployZone = deployZone;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getDeployZone() { return deployZone; }
    public List<VmGroupMember> getMembers() { return members; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setDeployZone(String deployZone) { this.deployZone = deployZone; }

    public void replaceMembers(List<VmGroupMember> newMembers) {
        this.members.clear();
        if (newMembers != null) {
            this.members.addAll(newMembers);
        }
    }
}

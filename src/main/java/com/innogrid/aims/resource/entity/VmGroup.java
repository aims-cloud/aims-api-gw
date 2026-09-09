package com.innogrid.aims.resource.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 가상서버 그룹.
 * 통합 토폴로지의 OpenStack 배포영역 내 VM들을 논리 그룹으로 묶는다.
 * 그룹에 담긴 가상서버(VmGroupMember)와 서버 간 연결(VmGroupConnection, 네트워크 맵)을 관리한다.
 * VM 생성/제어는 하지 않으며, 기존 VM을 담아 구성·연결만 관리한다.
 */
@Entity
@Table(name = "vm_groups")
public class VmGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 배포영역 (통합 토폴로지의 deployZone). */
    @Column(nullable = false, length = 100)
    private String deployZone;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /** 사용 여부 (화면 토글). */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 생성자 (인증 사용자 이름). */
    @Column(length = 100)
    private String createdBy;

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

    public VmGroup(String deployZone, String name, String description, boolean enabled, String createdBy) {
        this.deployZone = deployZone;
        this.name = name;
        this.description = description;
        this.enabled = enabled;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getDeployZone() { return deployZone; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
    public String getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setDeployZone(String deployZone) { this.deployZone = deployZone; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}

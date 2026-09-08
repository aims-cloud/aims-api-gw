package com.innogrid.aims.infra.ipam.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 네트워크 구역 (IPAM 최상위).
 * 사용자가 이름·설명을 붙인 논리 구역으로, 네트워크 인터페이스를 통해 실제 OpenStack 네트워크에 연결한다.
 */
@Entity
@Table(name = "network_zones")
public class NetworkZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 100)
    private String createdBy;

    @Column(length = 100)
    private String updatedBy;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }

    protected NetworkZone() {}

    public NetworkZone(String name, String description, String createdBy) {
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}

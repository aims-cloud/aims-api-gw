package com.innogrid.aims.infra.ipam.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * IP 주소 (서브넷 하위).
 *
 * - RESERVED(예약됨): 사용자가 수동 생성한 예약 IP. 자원에 할당되지 않은 상태.
 * - ALLOCATED(할당됨): OpenStack 포트에서 동기화된 실제 사용 IP. 자원/MAC/포트그룹이 채워진다.
 *
 * 별칭(alias)·설명(description)은 사용자 오버레이로, 동기화 시에도 보존한다.
 */
@Entity
@Table(name = "ip_addresses",
        uniqueConstraints = @UniqueConstraint(columnNames = {"subnet_id", "ip_address"}))
public class IpAddress {

    public enum Status { RESERVED, ALLOCATED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subnet_id", nullable = false)
    private Long subnetId;

    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    @Column(name = "ip_address", nullable = false, length = 60)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 100)
    private String alias;

    @Column(length = 500)
    private String description;

    // ── ALLOCATED 시 OpenStack 동기화 필드 ──────────────────────

    /** 자원 종류 (예: INSTANCE). */
    @Column(length = 30)
    private String resourceType;

    /** 할당된 자원 id (OpenStack 서버 id). */
    @Column(length = 100)
    private String resourceId;

    /** 할당된 자원 이름 (VM명). */
    @Column(length = 200)
    private String resourceName;

    @Column(length = 60)
    private String macAddress;

    /** 연결된 포트 그룹 (네트워크명, 예: external). */
    @Column(length = 200)
    private String portGroup;

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

    protected IpAddress() {}

    /** 예약 IP 생성. */
    public IpAddress(Long zoneId, Long subnetId, String ipAddress, String description, String createdBy) {
        this.zoneId = zoneId;
        this.subnetId = subnetId;
        this.ipAddress = ipAddress;
        this.status = Status.RESERVED;
        this.description = description;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    public Long getId() { return id; }
    public Long getSubnetId() { return subnetId; }
    public Long getZoneId() { return zoneId; }
    public String getIpAddress() { return ipAddress; }
    public Status getStatus() { return status; }
    public String getAlias() { return alias; }
    public String getDescription() { return description; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getResourceName() { return resourceName; }
    public String getMacAddress() { return macAddress; }
    public String getPortGroup() { return portGroup; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setAlias(String alias) { this.alias = alias; }
    public void setDescription(String description) { this.description = description; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    /** OpenStack 포트 정보로 할당 상태를 갱신한다 (별칭/설명은 유지). */
    public void applyAllocation(String resourceType, String resourceId, String resourceName,
                                String macAddress, String portGroup) {
        this.status = Status.ALLOCATED;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.resourceName = resourceName;
        this.macAddress = macAddress;
        this.portGroup = portGroup;
    }

    /** 할당 해제 (OpenStack에서 사라짐) → 예약 상태로 되돌리고 자원 정보 비움. */
    public void clearAllocation() {
        this.status = Status.RESERVED;
        this.resourceType = null;
        this.resourceId = null;
        this.resourceName = null;
        this.macAddress = null;
        this.portGroup = null;
    }
}

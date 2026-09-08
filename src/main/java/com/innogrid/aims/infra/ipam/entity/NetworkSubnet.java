package com.innogrid.aims.infra.ipam.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 서브넷 (네트워크 구역 하위).
 * 사용자가 정의한 CIDR 단위. IP 이용률은 소속 IP(예약+할당) 수 / CIDR 전체 호스트 수로 계산한다.
 */
@Entity
@Table(name = "network_subnets")
public class NetworkSubnet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    @Column(nullable = false, length = 50)
    private String cidr;

    @Column(length = 100)
    private String gateway;

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

    protected NetworkSubnet() {}

    public NetworkSubnet(Long zoneId, String cidr, String gateway, String description, String createdBy) {
        this.zoneId = zoneId;
        this.cidr = cidr;
        this.gateway = gateway;
        this.description = description;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    public Long getId() { return id; }
    public Long getZoneId() { return zoneId; }
    public String getCidr() { return cidr; }
    public String getGateway() { return gateway; }
    public String getDescription() { return description; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setGateway(String gateway) { this.gateway = gateway; }
    public void setDescription(String description) { this.description = description; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}

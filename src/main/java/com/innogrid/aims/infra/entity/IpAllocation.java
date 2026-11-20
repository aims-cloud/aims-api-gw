package com.innogrid.aims.infra.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "ip_allocations")
public class IpAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String mdcId;

    @Column(nullable = false, length = 45)
    private String address;

    @Column(nullable = false, length = 50)
    private String subnet;

    @Column(length = 100)
    private String assignedTo;

    @Column(nullable = false, length = 20)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IpStatus status = IpStatus.AVAILABLE;

    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected IpAllocation() {}

    public IpAllocation(String mdcId, String address, String subnet, String type) {
        this.mdcId = mdcId;
        this.address = address;
        this.subnet = subnet;
        this.type = type;
    }

    public Long getId() { return id; }
    public String getMdcId() { return mdcId; }
    public String getAddress() { return address; }
    public String getSubnet() { return subnet; }
    public String getAssignedTo() { return assignedTo; }
    public String getType() { return type; }
    public IpStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }
    public void setStatus(IpStatus status) { this.status = status; }

    public enum IpStatus {
        AVAILABLE, ALLOCATED, RESERVED
    }
}

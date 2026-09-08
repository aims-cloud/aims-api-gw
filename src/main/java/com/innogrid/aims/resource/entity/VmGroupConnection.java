package com.innogrid.aims.resource.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 가상서버 그룹 네트워크 맵의 방향성 연결.
 * 출발 서버의 엔드포인트(IP:PORT) → 도착 서버의 엔드포인트(IP:PORT)를 사용자가 정의한다.
 */
@Entity
@Table(name = "vm_group_connections")
public class VmGroupConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vm_group_id", nullable = false)
    private Long vmGroupId;

    @Column(name = "source_server_id", nullable = false, length = 100)
    private String sourceServerId;

    /** 출발 엔드포인트 (예: 10.0.0.39:1). */
    @Column(name = "source_endpoint", length = 100)
    private String sourceEndpoint;

    @Column(name = "target_server_id", nullable = false, length = 100)
    private String targetServerId;

    /** 도착 엔드포인트 (예: 192.168.110.65:1). */
    @Column(name = "target_endpoint", length = 100)
    private String targetEndpoint;

    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected VmGroupConnection() {}

    public VmGroupConnection(Long vmGroupId, String sourceServerId, String sourceEndpoint,
                             String targetServerId, String targetEndpoint) {
        this.vmGroupId = vmGroupId;
        this.sourceServerId = sourceServerId;
        this.sourceEndpoint = sourceEndpoint;
        this.targetServerId = targetServerId;
        this.targetEndpoint = targetEndpoint;
    }

    public Long getId() { return id; }
    public Long getVmGroupId() { return vmGroupId; }
    public String getSourceServerId() { return sourceServerId; }
    public String getSourceEndpoint() { return sourceEndpoint; }
    public String getTargetServerId() { return targetServerId; }
    public String getTargetEndpoint() { return targetEndpoint; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

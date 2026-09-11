package com.innogrid.aims.resource.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 가상서버 그룹에 담긴 가상서버.
 * OpenStack 서버 id로 참조하며, 표시용 정보(호스트/이름/IP)는 담을 때 스냅샷하고
 * 상태는 조회 시 Nova에서 실시간으로 채운다.
 */
@Entity
@Table(name = "vm_group_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"vm_group_id", "server_id"}))
public class VmGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vm_group_id", nullable = false)
    private Long vmGroupId;

    /** OpenStack 서버 id. */
    @Column(name = "server_id", nullable = false, length = 100)
    private String serverId;

    @Column(length = 200)
    private String vmName;

    /** 물리 호스트 (하이퍼바이저 호스트명). */
    @Column(length = 100)
    private String host;

    /** 대표 IP. */
    @Column(length = 100)
    private String ip;

    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected VmGroupMember() {}

    public VmGroupMember(Long vmGroupId, String serverId, String vmName, String host, String ip) {
        this.vmGroupId = vmGroupId;
        this.serverId = serverId;
        this.vmName = vmName;
        this.host = host;
        this.ip = ip;
    }

    public Long getId() { return id; }
    public Long getVmGroupId() { return vmGroupId; }
    public String getServerId() { return serverId; }
    public String getVmName() { return vmName; }
    public String getHost() { return host; }
    public String getIp() { return ip; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

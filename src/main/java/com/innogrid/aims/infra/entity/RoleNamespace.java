package com.innogrid.aims.infra.entity;

import java.time.OffsetDateTime;

import com.innogrid.aims.auth.entity.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 역할(Role) ↔ CB-Tumblebug Namespace 매핑 — 자원 탐색기 가상머신 가지의 역할별 접근 범위 원장.
 * 물리 자원은 {@link RoleAsset}(Role → Asset.id), 가상머신은 이 매핑(Role → Tumblebug nsId)으로 연결한다
 * (docs/06 자원 탐색기 트리 설계 §9.1). Tumblebug Label 에는 권한 정보를 저장하지 않는다.
 */
@Entity
@Table(name = "role_namespaces",
        uniqueConstraints = @UniqueConstraint(name = "uq_role_namespaces_role_ns",
                columnNames = {"role_id", "ns_id"}))
public class RoleNamespace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    /** Tumblebug Namespace ID (NsInfo.id) */
    @Column(name = "ns_id", nullable = false, length = 100)
    private String nsId;

    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected RoleNamespace() {}

    public RoleNamespace(Role role, String nsId) {
        this.role = role;
        this.nsId = nsId;
    }

    public Long getId() { return id; }
    public Role getRole() { return role; }
    public String getNsId() { return nsId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

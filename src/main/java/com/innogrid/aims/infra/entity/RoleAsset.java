package com.innogrid.aims.infra.entity;

import java.time.OffsetDateTime;

import com.innogrid.aims.auth.entity.Role;
import jakarta.persistence.*;

/**
 * 역할(Role) ↔ 자산(Asset) 매핑 — 자원 탐색기의 역할별 자원 스코프 원장.
 * 자산 하나가 여러 역할에 노출될 수 있는 N:M 구조이며,
 * 사용자는 토큰에 부여된 역할에 매핑된 자산만 트리·패널에서 볼 수 있다.
 */
@Entity
@Table(name = "role_assets",
        uniqueConstraints = @UniqueConstraint(name = "uq_role_assets_role_asset",
                columnNames = {"role_id", "asset_id"}))
public class RoleAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected RoleAsset() {}

    public RoleAsset(Role role, Asset asset) {
        this.role = role;
        this.asset = asset;
    }

    public Long getId() { return id; }
    public Role getRole() { return role; }
    public Asset getAsset() { return asset; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

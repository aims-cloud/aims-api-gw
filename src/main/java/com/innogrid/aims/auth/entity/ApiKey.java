package com.innogrid.aims.auth.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.*;

/**
 * 세부과제 간 서비스-서비스 통신용 API Key.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String keyValue;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String subProject;

    private boolean active = true;

    private OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;

    protected ApiKey() {}

    public ApiKey(String name, String subProject, OffsetDateTime expiresAt) {
        this.keyValue = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        this.name = name;
        this.subProject = subProject;
        this.expiresAt = expiresAt;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getKeyValue() { return keyValue; }
    public String getName() { return name; }
    public String getSubProject() { return subProject; }
    public boolean isActive() { return active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }

    public void setActive(boolean active) { this.active = active; }

    public boolean isValid() {
        return active && (expiresAt == null || expiresAt.isAfter(OffsetDateTime.now()));
    }
}

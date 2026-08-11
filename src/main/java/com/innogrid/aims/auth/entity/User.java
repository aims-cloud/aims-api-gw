package com.innogrid.aims.auth.entity;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 100)
    private String organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }

    protected User() {}

    public User(String email, String password, String name, String organization) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.organization = organization;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public String getName() { return name; }
    public String getOrganization() { return organization; }
    public UserStatus getStatus() { return status; }
    public Set<Role> getRoles() { return roles; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setPassword(String password) { this.password = password; }
    public void setName(String name) { this.name = name; }
    public void setOrganization(String organization) { this.organization = organization; }
    public void setStatus(UserStatus status) { this.status = status; }
    public void addRole(Role role) { this.roles.add(role); }
    public void removeRole(Role role) { this.roles.remove(role); }

    public enum UserStatus {
        ACTIVE, INACTIVE, PENDING
    }
}

package com.innogrid.aims.auth.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "permissions", uniqueConstraints =
    @UniqueConstraint(columnNames = {"resource", "action"}))
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String resource;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(length = 200)
    private String description;

    protected Permission() {}

    public Permission(String resource, String action, String description) {
        this.resource = resource;
        this.action = action;
        this.description = description;
    }

    public Long getId() { return id; }
    public String getResource() { return resource; }
    public String getAction() { return action; }
    public String getDescription() { return description; }
}

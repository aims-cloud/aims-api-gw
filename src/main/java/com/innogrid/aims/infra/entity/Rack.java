package com.innogrid.aims.infra.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "racks")
public class Rack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String mdcId;

    @Column(nullable = false, length = 100)
    private String name;

    private int totalUnits = 42;
    private double powerCapacityKw;

    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected Rack() {}

    public Rack(String mdcId, String name, int totalUnits, double powerCapacityKw) {
        this.mdcId = mdcId;
        this.name = name;
        this.totalUnits = totalUnits;
        this.powerCapacityKw = powerCapacityKw;
    }

    public Long getId() { return id; }
    public String getMdcId() { return mdcId; }
    public String getName() { return name; }
    public int getTotalUnits() { return totalUnits; }
    public double getPowerCapacityKw() { return powerCapacityKw; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
    public void setTotalUnits(int totalUnits) { this.totalUnits = totalUnits; }
    public void setPowerCapacityKw(double powerCapacityKw) { this.powerCapacityKw = powerCapacityKw; }
}

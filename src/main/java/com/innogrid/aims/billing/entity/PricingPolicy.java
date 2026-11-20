package com.innogrid.aims.billing.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

@Entity
@Table(name = "pricing_policies")
public class PricingPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String metricName;

    @Column(nullable = false, length = 20)
    private String unit;

    private double unitPrice;

    @Column(length = 200)
    private String description;

    private boolean active = true;
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected PricingPolicy() {}

    public PricingPolicy(String metricName, String unit, double unitPrice, String description) {
        this.metricName = metricName;
        this.unit = unit;
        this.unitPrice = unitPrice;
        this.description = description;
    }

    public Long getId() { return id; }
    public String getMetricName() { return metricName; }
    public String getUnit() { return unit; }
    public double getUnitPrice() { return unitPrice; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setMetricName(String metricName) { this.metricName = metricName; }
    public void setUnit(String unit) { this.unit = unit; }
    public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }
    public void setDescription(String description) { this.description = description; }
    public void setActive(boolean active) { this.active = active; }
}

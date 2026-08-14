package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 스케줄링 정책.
 * 자원 배치 시 고려할 가중치와 제약 조건을 정의한다.
 */
@Entity
@Table(name = "scheduling_policies")
public class SchedulingPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 300)
    private String description;

    // ── 스코어링 가중치 (합계 1.0) ──────────────────────────────
    private double cpuWeight = 0.25;
    private double memoryWeight = 0.25;
    private double powerWeight = 0.20;
    private double temperatureWeight = 0.15;
    private double networkWeight = 0.15;

    // ── 제약 조건 임계치 ────────────────────────────────────────
    private double maxCpuUsagePercent = 80.0;
    private double maxMemoryUsagePercent = 85.0;
    private double maxTemperatureCelsius = 75.0;
    private double maxPowerUsagePercent = 90.0;

    // ── 전력 효율성 설정 ────────────────────────────────────────
    private boolean powerAwareEnabled = true;
    private boolean temperatureAwareEnabled = true;
    private boolean affinityEnabled = false;

    private boolean active = true;
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected SchedulingPolicy() {}

    public SchedulingPolicy(String name, String description) {
        this.name = name;
        this.description = description;
    }

    // Getters
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public double getCpuWeight() { return cpuWeight; }
    public double getMemoryWeight() { return memoryWeight; }
    public double getPowerWeight() { return powerWeight; }
    public double getTemperatureWeight() { return temperatureWeight; }
    public double getNetworkWeight() { return networkWeight; }
    public double getMaxCpuUsagePercent() { return maxCpuUsagePercent; }
    public double getMaxMemoryUsagePercent() { return maxMemoryUsagePercent; }
    public double getMaxTemperatureCelsius() { return maxTemperatureCelsius; }
    public double getMaxPowerUsagePercent() { return maxPowerUsagePercent; }
    public boolean isPowerAwareEnabled() { return powerAwareEnabled; }
    public boolean isTemperatureAwareEnabled() { return temperatureAwareEnabled; }
    public boolean isAffinityEnabled() { return affinityEnabled; }
    public boolean isActive() { return active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    // Setters
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setCpuWeight(double cpuWeight) { this.cpuWeight = cpuWeight; }
    public void setMemoryWeight(double memoryWeight) { this.memoryWeight = memoryWeight; }
    public void setPowerWeight(double powerWeight) { this.powerWeight = powerWeight; }
    public void setTemperatureWeight(double temperatureWeight) { this.temperatureWeight = temperatureWeight; }
    public void setNetworkWeight(double networkWeight) { this.networkWeight = networkWeight; }
    public void setMaxCpuUsagePercent(double v) { this.maxCpuUsagePercent = v; }
    public void setMaxMemoryUsagePercent(double v) { this.maxMemoryUsagePercent = v; }
    public void setMaxTemperatureCelsius(double v) { this.maxTemperatureCelsius = v; }
    public void setMaxPowerUsagePercent(double v) { this.maxPowerUsagePercent = v; }
    public void setPowerAwareEnabled(boolean v) { this.powerAwareEnabled = v; }
    public void setTemperatureAwareEnabled(boolean v) { this.temperatureAwareEnabled = v; }
    public void setAffinityEnabled(boolean v) { this.affinityEnabled = v; }
    public void setActive(boolean active) { this.active = active; }
}

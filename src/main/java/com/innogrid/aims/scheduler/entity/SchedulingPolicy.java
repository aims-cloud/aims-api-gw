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

    /**
     * 이 정책이 적용될 리소스 종류. null이면 종류를 가리지 않는 공통 정책이다.
     * 활성 정책은 종류별 1개 + 공통 1개까지 공존할 수 있다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Workload.ResourceType resourceType;

    /** 스케줄 실행과 엔진을 비운 단건·모의 배치가 이 정책으로 배치할 때 쓸 엔진 id. null이면 weighted. */
    @Column(length = 30)
    private String defaultEngine;

    /**
     * 경고 온도(℃). 이 값을 넘으면 배치를 막지는 않고 상태만 WARNING으로 본다.
     * null이면 설정 기본값을 쓴다.
     */
    private Double warningTemperatureCelsius;

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

    /**
     * 영속되지 않는 사본 — 저장된 정책을 고치지 않고 이번 실행이나 비교 판에서만 값을 바꿔 쓰려는 쪽이 부른다.
     *
     * <p>판단 필드 전부(이름·설명·종류·기본 엔진·경고 온도·가중치 5·임계 4·스위치 3·활성 여부)를 옮기고
     * {@code id}와 {@code createdAt}은 옮기지 않는다 — {@code id} 세터가 없어 사본의 {@code id}는 항상 {@code null}
     * 이라 JPA가 기존 행과 동일시할 경로가 없고, {@code createdAt}은 저장될 때 {@code PrePersist}가 채우는 값이다.
     * 필드를 하나 빠뜨리면 그 사본을 쓰는 경로에서 게이트가 조용히 꺼진다. 예를 들어 {@code affinityEnabled}가
     * 빠지면 MDC 링크 게이트가 꺼진다.
     * 테스트 {@code SchedulingPolicyCopyTest}가 선언된 필드 전량을 반사로 훑어 확인한다.</p>
     */
    public SchedulingPolicy detachedCopy() {
        SchedulingPolicy copy = new SchedulingPolicy(getName(), getDescription());

        copy.setResourceType(getResourceType());
        copy.setDefaultEngine(getDefaultEngine());
        copy.setWarningTemperatureCelsius(getWarningTemperatureCelsius());

        copy.setCpuWeight(getCpuWeight());
        copy.setMemoryWeight(getMemoryWeight());
        copy.setPowerWeight(getPowerWeight());
        copy.setTemperatureWeight(getTemperatureWeight());
        copy.setNetworkWeight(getNetworkWeight());

        copy.setMaxCpuUsagePercent(getMaxCpuUsagePercent());
        copy.setMaxMemoryUsagePercent(getMaxMemoryUsagePercent());
        copy.setMaxTemperatureCelsius(getMaxTemperatureCelsius());
        copy.setMaxPowerUsagePercent(getMaxPowerUsagePercent());

        copy.setPowerAwareEnabled(isPowerAwareEnabled());
        copy.setTemperatureAwareEnabled(isTemperatureAwareEnabled());
        copy.setAffinityEnabled(isAffinityEnabled());

        copy.setActive(isActive());
        return copy;
    }

    // Getters
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Workload.ResourceType getResourceType() { return resourceType; }
    public String getDefaultEngine() { return defaultEngine; }
    public Double getWarningTemperatureCelsius() { return warningTemperatureCelsius; }
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
    public void setResourceType(Workload.ResourceType resourceType) { this.resourceType = resourceType; }
    public void setDefaultEngine(String defaultEngine) { this.defaultEngine = defaultEngine; }
    public void setWarningTemperatureCelsius(Double v) { this.warningTemperatureCelsius = v; }
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

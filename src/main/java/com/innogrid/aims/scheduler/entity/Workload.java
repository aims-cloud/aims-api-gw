package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 스케줄링 대상 워크로드.
 * CPU, 메모리, GPU 등의 자원 요구사항을 정의하고
 * 스케줄링 엔진이 최적 노드에 배치한다.
 */
@Entity
@Table(name = "workloads")
public class Workload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 300)
    private String description;

    // ── 자원 요구사항 ───────────────────────────────────────────
    private int requiredCpu;
    private int requiredMemoryMb;
    private int requiredGpu;
    private int requiredDiskGb;

    // ── 제약 조건 ───────────────────────────────────────────────
    @Column(length = 50)
    private String preferredMdcId;

    private Double maxPowerBudgetWatts;
    private Double maxTemperatureCelsius;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkloadPriority priority = WorkloadPriority.NORMAL;

    // ── 배치 결과 ───────────────────────────────────────────────
    @Column(length = 50)
    private String assignedMdcId;

    @Column(length = 100)
    private String assignedNodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkloadStatus status = WorkloadStatus.PENDING;

    private Double schedulingScore;

    @Column(length = 500)
    private String schedulingReason;

    private OffsetDateTime createdAt;
    private OffsetDateTime scheduledAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected Workload() {}

    public Workload(String name, int requiredCpu, int requiredMemoryMb) {
        this.name = name;
        this.requiredCpu = requiredCpu;
        this.requiredMemoryMb = requiredMemoryMb;
    }

    // Getters
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getRequiredCpu() { return requiredCpu; }
    public int getRequiredMemoryMb() { return requiredMemoryMb; }
    public int getRequiredGpu() { return requiredGpu; }
    public int getRequiredDiskGb() { return requiredDiskGb; }
    public String getPreferredMdcId() { return preferredMdcId; }
    public Double getMaxPowerBudgetWatts() { return maxPowerBudgetWatts; }
    public Double getMaxTemperatureCelsius() { return maxTemperatureCelsius; }
    public WorkloadPriority getPriority() { return priority; }
    public String getAssignedMdcId() { return assignedMdcId; }
    public String getAssignedNodeId() { return assignedNodeId; }
    public WorkloadStatus getStatus() { return status; }
    public Double getSchedulingScore() { return schedulingScore; }
    public String getSchedulingReason() { return schedulingReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getScheduledAt() { return scheduledAt; }

    // Setters
    public void setDescription(String description) { this.description = description; }
    public void setRequiredCpu(int requiredCpu) { this.requiredCpu = requiredCpu; }
    public void setRequiredMemoryMb(int requiredMemoryMb) { this.requiredMemoryMb = requiredMemoryMb; }
    public void setRequiredGpu(int requiredGpu) { this.requiredGpu = requiredGpu; }
    public void setRequiredDiskGb(int requiredDiskGb) { this.requiredDiskGb = requiredDiskGb; }
    public void setPreferredMdcId(String preferredMdcId) { this.preferredMdcId = preferredMdcId; }
    public void setMaxPowerBudgetWatts(Double maxPowerBudgetWatts) { this.maxPowerBudgetWatts = maxPowerBudgetWatts; }
    public void setMaxTemperatureCelsius(Double maxTemperatureCelsius) { this.maxTemperatureCelsius = maxTemperatureCelsius; }
    public void setPriority(WorkloadPriority priority) { this.priority = priority; }

    public void assignTo(String mdcId, String nodeId, double score, String reason) {
        this.assignedMdcId = mdcId;
        this.assignedNodeId = nodeId;
        this.schedulingScore = score;
        this.schedulingReason = reason;
        this.status = WorkloadStatus.SCHEDULED;
        this.scheduledAt = OffsetDateTime.now();
    }

    public void markRunning() { this.status = WorkloadStatus.RUNNING; }
    public void markCompleted() { this.status = WorkloadStatus.COMPLETED; }
    public void markFailed(String reason) {
        this.status = WorkloadStatus.FAILED;
        this.schedulingReason = reason;
    }

    public enum WorkloadStatus {
        PENDING, SCHEDULED, RUNNING, COMPLETED, FAILED
    }

    public enum WorkloadPriority {
        LOW, NORMAL, HIGH, CRITICAL
    }
}

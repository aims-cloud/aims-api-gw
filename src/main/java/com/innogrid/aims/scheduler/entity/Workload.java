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

    /**
     * 리소스 종류. 스케줄이 이 값으로 배치 대상을 고른다.
     * 기존 행은 null 이며 {@link #resolveResourceType()} 이 VM 으로 해석한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ResourceType resourceType;

    // ── 제약 조건 ───────────────────────────────────────────────
    @Column(length = 50)
    private String preferredMdcId;

    /** 이 워크로드가 다루는 데이터가 있는 MDC. MDC 간 링크 비용 계산에 쓴다. */
    @Column(length = 50)
    private String dataMdcId;

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
    public ResourceType getResourceType() { return resourceType; }
    public String getDataMdcId() { return dataMdcId; }
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
    public void setResourceType(ResourceType resourceType) { this.resourceType = resourceType; }
    public void setDataMdcId(String dataMdcId) { this.dataMdcId = dataMdcId; }
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

    /**
     * 실행 상태를 전이한다. 허용되지 않는 전이는 false 를 돌려준다.
     */
    public boolean transitionTo(WorkloadStatus target) {
        if (target == null || !status.canTransitionTo(target)) return false;
        this.status = target;
        return true;
    }

    /** 값이 없으면 VM 으로 해석한 리소스 종류. */
    public ResourceType resolveResourceType() {
        return ResourceType.orDefault(resourceType);
    }

    public enum WorkloadStatus {
        PENDING, SCHEDULED, RUNNING, COMPLETED, FAILED;

        /**
         * 이 상태에서 대상 상태로 넘어갈 수 있는지 판단한다.
         * 배치(PENDING→SCHEDULED)와 실패 처리는 스케줄링 엔진이 직접 수행하므로
         * 여기서는 배치 이후의 실행 흐름만 다룬다.
         */
        public boolean canTransitionTo(WorkloadStatus target) {
            return switch (this) {
                case SCHEDULED -> target == RUNNING || target == FAILED;
                case RUNNING -> target == COMPLETED || target == FAILED;
                default -> false;
            };
        }
    }

    /**
     * 리소스 종류.
     * 값을 추가할 때는 DB 의 CHECK 제약도 함께 바꿔야 한다(개발순서.md 부록 A).
     */
    public enum ResourceType {
        VM, GPU, NPU, CONTAINER;

        /** 기존 행처럼 값이 없으면 VM 으로 본다. */
        public static ResourceType orDefault(ResourceType value) {
            return value != null ? value : VM;
        }
    }

    public enum WorkloadPriority {
        LOW, NORMAL, HIGH, CRITICAL
    }
}

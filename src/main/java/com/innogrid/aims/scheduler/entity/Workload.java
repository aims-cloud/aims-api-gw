package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 스케줄링 대상 워크로드.
 * CPU, 메모리, GPU 등의 자원 요구사항을 정의하고
 * 스케줄링 엔진이 최적 노드에 배치한다.
 */
@Entity
@Table(name = "workloads", indexes = {
        // 스케줄 실행이 매 틱마다 도는 배치 후보 조회의 필터 두 개
        @Index(name = "idx_workloads_status_type", columnList = "status, resourceType")
})
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

    /**
     * 배치 실패 누적 횟수.
     *
     * <p>스케줄 실행은 FAILED 워크로드도 대상으로 잡아 다시 배치한다. 그런데 자원이 영영
     * 모자란 워크로드는 매 실행마다 실패해 실행 이력을 끝없이 채운다(5분 주기면 하루 288건).
     * 이 값이 상한을 넘으면 대상에서 제외한다.</p>
     *
     * <p>기존 행은 null 이며 {@link #resolveRetryCount()} 가 0 으로 해석한다.</p>
     */
    private Integer retryCount;

    /**
     * 마지막으로 배치에 실패한 시각.
     *
     * <p>재시도를 횟수로만 막으면 5분 주기 스케줄은 25분에 다섯 번을 전부 태운다. 그런데 실패
     * 원인 중에는 저절로 회복되는 것이 있다 — 임계 온도를 넘긴 노드의 냉각 대기, 아직 도착하지
     * 않은 메트릭. 둘 다 10분 눈금이라, 그보다 짧은 간격의 재시도는 회복 전에 기회를 소모한다.
     * 이 시각을 남겨 두면 후보 질의가 "마지막 실패로부터 얼마나 지났는가" 를 함께 볼 수 있다.</p>
     *
     * <p>기존 행은 null 이고, null 이면 간격 제한 없이 대상이 된다 — 백오프를 도입하기 전에
     * 실패한 워크로드를 새 규칙으로 소급해 묶어 두지 않는다.</p>
     */
    private OffsetDateTime lastFailedAt;

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
    public Integer getRetryCount() { return retryCount; }
    public OffsetDateTime getLastFailedAt() { return lastFailedAt; }
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
        if (this.retryCount == null) this.retryCount = 0;   // 기존 행의 응답 계약(null → 0)은 유지하되 실패 누적은 지우지 않는다
    }

    public void markRunning() { this.status = WorkloadStatus.RUNNING; }
    public void markCompleted() {
        this.status = WorkloadStatus.COMPLETED;
        clearRetryAccounting();
    }

    /** 배치에 실패했다. 재시도 횟수를 누적해 무한 재시도를 막는다. */
    public void markFailed(String reason) {
        this.status = WorkloadStatus.FAILED;
        this.schedulingReason = reason;
        this.retryCount = resolveRetryCount() + 1;
        this.lastFailedAt = OffsetDateTime.now();
    }

    /**
     * 실행 계층이 배치 요청을 거부했거나 닿지 못했다 — 실패로 돌리고 <b>배정을 해제</b>한다.
     *
     * <p>엔진 실패({@link #markFailed})는 배정 컬럼을 건드리지 않지만, 이 경로는 이미 노드가 정해진 뒤 실행이 무산된
     * 것이어서 배정을 남기면 화면에 "실패했는데 노드가 있는" 상태가 보인다. {@code scheduledAt} 은 추적을 위해 남긴다.
     * 재시도 횟수는 엔진 실패와 같이 누적된다 — 실행 계층 거부가 반복되면 상한에서 대상에서 빠진다.</p>
     */
    public void failDispatch(String reason) {
        markFailed(reason);
        this.assignedMdcId = null;
        this.assignedNodeId = null;
        this.schedulingScore = null;
    }

    /**
     * 실행 상태를 전이한다. 허용되지 않는 전이는 false 를 돌려준다.
     *
     * <p>FAILED 에서 PENDING 으로 되돌릴 때는 재시도 횟수를 지운다. 그러지 않으면
     * 되살려도 곧바로 상한에 걸려 다시 대상에서 빠진다.</p>
     */
    public boolean transitionTo(WorkloadStatus target) {
        if (target == null || !status.canTransitionTo(target)) return false;
        if (status == WorkloadStatus.FAILED && target == WorkloadStatus.PENDING) {
            clearRetryAccounting();
            this.schedulingReason = null;
        }
        this.status = target;
        if (target == WorkloadStatus.COMPLETED) clearRetryAccounting();
        return true;
    }

    private void clearRetryAccounting() {
        this.retryCount = 0;
        this.lastFailedAt = null;
    }

    /** 값이 없으면 VM 으로 해석한 리소스 종류. */
    public ResourceType resolveResourceType() {
        return ResourceType.orDefault(resourceType);
    }

    /** 값이 없으면 0 으로 해석한 재시도 횟수. */
    public int resolveRetryCount() {
        return retryCount == null ? 0 : retryCount;
    }

    public enum WorkloadStatus {
        PENDING, SCHEDULED, RUNNING, COMPLETED, FAILED;

        /**
         * 이 상태에서 대상 상태로 넘어갈 수 있는지 판단한다.
         * 배치(PENDING→SCHEDULED)와 실패 처리는 스케줄링 엔진이 직접 수행하므로
         * 여기서는 배치 이후의 실행 흐름만 다룬다.
         *
         * <p>FAILED→PENDING 만 예외로 허용한다. 재시도 상한에 걸려 대상에서 빠진 워크로드를
         * 운영자가 되살릴 유일한 경로다.</p>
         */
        public boolean canTransitionTo(WorkloadStatus target) {
            return switch (this) {
                case SCHEDULED -> target == RUNNING || target == FAILED;
                case RUNNING -> target == COMPLETED || target == FAILED;
                case FAILED -> target == PENDING;
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

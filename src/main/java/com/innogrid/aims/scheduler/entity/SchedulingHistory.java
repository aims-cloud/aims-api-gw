package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 스케줄링 결정 이력.
 * 어떤 워크로드가 어떤 노드에 왜 배치되었는지 기록한다.
 */
@Entity
@Table(name = "scheduling_history", indexes = {
        // 이 표는 지워지지 않는다(연구 근거 보존) — 단조 증가하므로 조회는 전부 인덱스를 타야 한다.
        // 실행 상세 화면: 한 실행이 만든 결정 목록
        @Index(name = "idx_scheduling_history_run", columnList = "scheduleRunId"),
        // 워크로드별 이력: 특정 워크로드의 결정 흐름
        @Index(name = "idx_scheduling_history_workload_created", columnList = "workloadId, createdAt"),
        // 전체 이력·페이징의 기본 정렬
        @Index(name = "idx_scheduling_history_created", columnList = "createdAt")
})
public class SchedulingHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workloadId;

    @Column(length = 100)
    private String workloadName;

    @Column(length = 50)
    private String selectedMdcId;

    @Column(length = 100)
    private String selectedNodeId;

    private double finalScore;

    // ── 개별 스코어 상세 ────────────────────────────────────────
    private double cpuScore;
    private double memoryScore;
    private double powerScore;
    private double temperatureScore;
    private double networkScore;

    // ── 배치 시점 노드 상태 ─────────────────────────────────────
    private double nodeCpuUsage;
    private double nodeMemoryUsage;
    private double nodePowerWatts;
    private double nodeTemperature;

    private int candidateCount;

    @Column(length = 500)
    private String reason;

    /**
     * 후보 노드의 제외 사유 코드를 코드별로 센 JSON 객체 문자열 — 예 {@code {"COOLING_DOWN":1,"INSUFFICIENT_RESOURCE":3}}.
     * 키는 코드 이름 오름차순, 값은 그 코드로 빠진 후보 수. 모집단은 {@code candidateCount} 와 같다 — 수집 단계에서 빠진
     * 노드는 후보가 아니라 여기에도 없고, 적격 후보는 세지 않는다. 한 노드는 먼저 걸린 제약 하나로만 세므로(C1→C6 순,
     * 배치 응답의 {@code exclusionCode} 와 같은 규칙) 값의 합은 부적격 후보 수와 같다. 제외가 없으면 {@code null}(빈 객체
     * 아님), 이 열이 생기기 전의 행도 {@code null}. 엔진이 배치 시점에 본 집계라 실행 계층이 그 뒤 배치를 되돌린 건도 그대로
     * 남는다 — 이력 응답과 실행 상세 결정 목록이 이 열을 그대로 싣는다.
     *
     * <p>코드 하나를 열거형 열로 남기는 대안을 버리고 집계 문자열을 평문으로 둔 까닭: Hibernate 는 열거형 열을 처음 만들 때
     * 그 시점 값 목록으로 CHECK 를 자동 생성하고 뒤에 값이 늘어도 고치지 않는다 — 제외 코드는 제약이 늘면 함께 늘어나는
     * 목록이다.</p>
     */
    @Column(columnDefinition = "text")
    private String exclusionCodes;

    @Column(length = 50)
    private String engineName;

    @Column(length = 100)
    private String policyName;

    /**
     * 이 결정을 만든 스케줄 실행({@code schedule_runs.id}).
     *
     * <p>단건 스케줄링 API 로 만든 이력은 null 이다. 외래키를 두지 않았으므로 실행 이력을
     * 지울 때 DB 가 알아서 정리해 주지 않는다 — 코드에서 명시적으로 null 로 만든다.
     * 배치 결정 이력 자체는 연구 근거 자료라 함께 지우지 않는다.</p>
     */
    private Long scheduleRunId;

    /** 배치 성공 여부. 기존 행은 성공한 결정만 저장했으므로 null 은 성공으로 본다. */
    private Boolean success;

    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { this.createdAt = OffsetDateTime.now(); }

    protected SchedulingHistory() {}

    public SchedulingHistory(Long workloadId, String workloadName, String selectedMdcId,
                             String selectedNodeId, double finalScore, String reason,
                             String engineName, String policyName) {
        this.workloadId = workloadId;
        this.workloadName = workloadName;
        this.selectedMdcId = selectedMdcId;
        this.selectedNodeId = selectedNodeId;
        this.finalScore = finalScore;
        this.reason = reason;
        this.engineName = engineName;
        this.policyName = policyName;
    }

    // Getters
    public Long getId() { return id; }
    public Long getWorkloadId() { return workloadId; }
    public String getWorkloadName() { return workloadName; }
    public String getSelectedMdcId() { return selectedMdcId; }
    public String getSelectedNodeId() { return selectedNodeId; }
    public double getFinalScore() { return finalScore; }
    public double getCpuScore() { return cpuScore; }
    public double getMemoryScore() { return memoryScore; }
    public double getPowerScore() { return powerScore; }
    public double getTemperatureScore() { return temperatureScore; }
    public double getNetworkScore() { return networkScore; }
    public double getNodeCpuUsage() { return nodeCpuUsage; }
    public double getNodeMemoryUsage() { return nodeMemoryUsage; }
    public double getNodePowerWatts() { return nodePowerWatts; }
    public double getNodeTemperature() { return nodeTemperature; }
    public int getCandidateCount() { return candidateCount; }
    public String getReason() { return reason; }
    public String getExclusionCodes() { return exclusionCodes; }
    public String getEngineName() { return engineName; }
    public String getPolicyName() { return policyName; }
    public Long getScheduleRunId() { return scheduleRunId; }
    public Boolean getSuccess() { return success; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /** 성공 여부를 남기고, 스케줄 실행이 만든 결정이면 그 실행에 연결한다({@code scheduleRunId} 가 null 이면 연결 없이 성공 여부만). */
    public void linkToRun(Long scheduleRunId, boolean success) {
        this.scheduleRunId = scheduleRunId;
        this.success = success;
    }

    /** 실행 이력이 지워질 때 연결만 끊는다. 결정 내용은 남긴다. */
    public void unlinkRun() { this.scheduleRunId = null; }

    // Setters for score details
    public void setScoreDetails(double cpu, double memory, double power, double temperature, double network) {
        this.cpuScore = cpu;
        this.memoryScore = memory;
        this.powerScore = power;
        this.temperatureScore = temperature;
        this.networkScore = network;
    }

    public void setNodeStatus(double cpuUsage, double memUsage, double powerWatts, double temperature) {
        this.nodeCpuUsage = cpuUsage;
        this.nodeMemoryUsage = memUsage;
        this.nodePowerWatts = powerWatts;
        this.nodeTemperature = temperature;
    }

    public void setCandidateCount(int count) { this.candidateCount = count; }

    public void setExclusionCodes(String exclusionCodes) { this.exclusionCodes = exclusionCodes; }
}

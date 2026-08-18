package com.innogrid.aims.scheduler.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

/**
 * 스케줄링 결정 이력.
 * 어떤 워크로드가 어떤 노드에 왜 배치되었는지 기록한다.
 */
@Entity
@Table(name = "scheduling_history")
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

    @Column(length = 50)
    private String engineName;

    @Column(length = 100)
    private String policyName;

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
    public String getEngineName() { return engineName; }
    public String getPolicyName() { return policyName; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

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
}

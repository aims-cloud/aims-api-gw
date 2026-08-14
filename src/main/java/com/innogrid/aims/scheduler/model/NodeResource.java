package com.innogrid.aims.scheduler.model;

/**
 * 노드의 실시간 자원 상태.
 * Thanos/Prometheus 메트릭에서 수집한 데이터를 담는다.
 * 스케줄링 엔진이 이 데이터를 기반으로 배치를 결정한다.
 */
public class NodeResource {

    private final String mdcId;
    private final String nodeId;

    // ── 자원 용량 ───────────────────────────────────────────────
    private final int totalCpu;
    private final long totalMemoryMb;
    private final int totalGpu;

    // ── 현재 사용률 (0.0 ~ 100.0) ───────────────────────────────
    private final double cpuUsagePercent;
    private final double memoryUsagePercent;
    private final double diskUsagePercent;

    // ── 전력/온도 (IPMI) ────────────────────────────────────────
    private final double powerWatts;
    private final double powerCapacityWatts;
    private final double temperatureCelsius;

    // ── 네트워크 ────────────────────────────────────────────────
    private final double networkRxBps;
    private final double networkTxBps;

    public NodeResource(String mdcId, String nodeId,
                        int totalCpu, long totalMemoryMb, int totalGpu,
                        double cpuUsagePercent, double memoryUsagePercent, double diskUsagePercent,
                        double powerWatts, double powerCapacityWatts, double temperatureCelsius,
                        double networkRxBps, double networkTxBps) {
        this.mdcId = mdcId;
        this.nodeId = nodeId;
        this.totalCpu = totalCpu;
        this.totalMemoryMb = totalMemoryMb;
        this.totalGpu = totalGpu;
        this.cpuUsagePercent = cpuUsagePercent;
        this.memoryUsagePercent = memoryUsagePercent;
        this.diskUsagePercent = diskUsagePercent;
        this.powerWatts = powerWatts;
        this.powerCapacityWatts = powerCapacityWatts;
        this.temperatureCelsius = temperatureCelsius;
        this.networkRxBps = networkRxBps;
        this.networkTxBps = networkTxBps;
    }

    // ── 계산 메서드 ─────────────────────────────────────────────

    /** 가용 CPU 코어 수 */
    public double availableCpu() {
        return totalCpu * (1.0 - cpuUsagePercent / 100.0);
    }

    /** 가용 메모리 (MB) */
    public long availableMemoryMb() {
        return (long) (totalMemoryMb * (1.0 - memoryUsagePercent / 100.0));
    }

    /** 전력 효율성 (작업량/와트) — 높을수록 효율적 */
    public double powerEfficiency() {
        if (powerWatts <= 0) return 0;
        return cpuUsagePercent / powerWatts;
    }

    /** 전력 여유율 (%) */
    public double powerHeadroomPercent() {
        if (powerCapacityWatts <= 0) return 100;
        return (1.0 - powerWatts / powerCapacityWatts) * 100;
    }

    /** 워크로드 수용 가능 여부 */
    public boolean canAccommodate(int requiredCpu, int requiredMemMb, int requiredGpu) {
        return availableCpu() >= requiredCpu
                && availableMemoryMb() >= requiredMemMb
                && totalGpu >= requiredGpu;
    }

    // Getters
    public String getMdcId() { return mdcId; }
    public String getNodeId() { return nodeId; }
    public int getTotalCpu() { return totalCpu; }
    public long getTotalMemoryMb() { return totalMemoryMb; }
    public int getTotalGpu() { return totalGpu; }
    public double getCpuUsagePercent() { return cpuUsagePercent; }
    public double getMemoryUsagePercent() { return memoryUsagePercent; }
    public double getDiskUsagePercent() { return diskUsagePercent; }
    public double getPowerWatts() { return powerWatts; }
    public double getPowerCapacityWatts() { return powerCapacityWatts; }
    public double getTemperatureCelsius() { return temperatureCelsius; }
    public double getNetworkRxBps() { return networkRxBps; }
    public double getNetworkTxBps() { return networkTxBps; }
}

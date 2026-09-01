package com.innogrid.aims.scheduler.model;

/**
 * 노드의 실시간 자원 상태.
 * Thanos/Prometheus 메트릭에서 수집한 데이터를 담는다.
 * 스케줄링 엔진이 이 데이터를 기반으로 배치를 결정한다.
 *
 * <p>메트릭 사용률은 "이미 실행 중인" 부하만 반영하므로, 배치는 되었으나 아직 메트릭에
 * 드러나지 않은 워크로드는 {@code reserved*} 로 따로 차감한다. 그래야 한 번의 스케줄링
 * 실행 안에서 같은 노드에 과도하게 배치되는 것을 막을 수 있다.</p>
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

    // ── 예약분 (배치 확정됐으나 메트릭에 아직 반영되지 않은 워크로드) ──
    private int reservedCpu;
    private long reservedMemoryMb;
    private int reservedGpu;

    // ── 부가 정보 (조회 응답용, 정책이 있어야 계산 가능) ──────────
    private ThermalState thermalState;
    private PowerSource powerSource = PowerSource.NONE;

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

    /** 가용 CPU 코어 수 (예약분 차감) */
    public double availableCpu() {
        return totalCpu * (1.0 - cpuUsagePercent / 100.0) - reservedCpu;
    }

    /** 가용 메모리 (MB, 예약분 차감) */
    public long availableMemoryMb() {
        return (long) (totalMemoryMb * (1.0 - memoryUsagePercent / 100.0)) - reservedMemoryMb;
    }

    /** 가용 GPU 개수 (예약분 차감) */
    public int availableGpu() {
        return totalGpu - reservedGpu;
    }

    /**
     * 예약분까지 반영한 CPU 사용률 (%).
     *
     * <p>메트릭은 "이미 실행 중인" 부하만 보여준다. 한 번의 스케줄링 실행에서 워크로드를
     * 잇달아 배치하면 예약만 쌓이고 사용률은 그대로라, 제약 검사가 매번 같은 값을 보게 된다.
     * 그래서 예약분을 사용률로 환산해 더한 값을 따로 둔다.</p>
     *
     * <p><b>이름에 {@code get} 을 붙이지 않는다.</b> 이 클래스는 DTO 없이 그대로 응답으로
     * 나가므로 {@code get} 접두어를 쓰면 API 에 필드가 늘어난다.
     * {@code NodeResourceSerializationTest} 가 이 규칙을 지킨다.</p>
     */
    public double effectiveCpuUsagePercent() {
        if (totalCpu <= 0) return cpuUsagePercent;
        return Math.min(100.0, cpuUsagePercent + (double) reservedCpu / totalCpu * 100.0);
    }

    /** 예약분까지 반영한 메모리 사용률 (%). {@link #effectiveCpuUsagePercent} 와 같은 이유로 둔다. */
    public double effectiveMemoryUsagePercent() {
        if (totalMemoryMb <= 0) return memoryUsagePercent;
        return Math.min(100.0, memoryUsagePercent + (double) reservedMemoryMb / totalMemoryMb * 100.0);
    }

    /** 예약분이 차지하는 CPU 비율 (0~1). 전력·온도 모델이 상승분을 계산할 때 쓴다. */
    public double reservedCpuRatio() {
        if (totalCpu <= 0) return 0;
        return Math.min(1.0, (double) reservedCpu / totalCpu);
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
                && availableGpu() >= requiredGpu;
    }

    /**
     * 배치 확정분을 예약으로 잡는다.
     * 같은 스케줄링 실행 안에서 다음 워크로드가 이 노드를 평가할 때 차감된 값이 보인다.
     */
    public void reserve(int cpu, long memoryMb, int gpu) {
        this.reservedCpu += cpu;
        this.reservedMemoryMb += memoryMb;
        this.reservedGpu += gpu;
    }

    /** 전력·온도 메트릭 수집 여부. */
    public enum PowerSource {
        /** IPMI 메트릭으로 실측한 값. */
        IPMI,
        /** 수집되지 않아 0 으로 채운 값. */
        NONE
    }

    /** 온도 상태. 임계값은 정책에서 오므로 외부에서 계산해 넣는다. */
    public enum ThermalState {
        NORMAL, WARNING, CRITICAL
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
    public int getReservedCpu() { return reservedCpu; }
    public long getReservedMemoryMb() { return reservedMemoryMb; }
    public int getReservedGpu() { return reservedGpu; }
    public ThermalState getThermalState() { return thermalState; }
    public void setThermalState(ThermalState thermalState) { this.thermalState = thermalState; }
    public PowerSource getPowerSource() { return powerSource; }
    public void setPowerSource(PowerSource powerSource) { this.powerSource = powerSource; }

    /** 조회 응답에서 가용량을 그대로 보여주기 위한 별칭. */
    public double getAvailableCpu() { return availableCpu(); }
    public long getAvailableMemoryMb() { return availableMemoryMb(); }
    public int getAvailableGpu() { return availableGpu(); }
}

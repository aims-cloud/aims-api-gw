package com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu;

/**
 * CPU 카테고리 모니터링 탭의 차트 지표 9종 — node_exporter {@code node_cpu_seconds_total} 의 mode 별 사용률(%).
 * VM CPU(VmCpuMetric)와 같은 세트다. CPU_USED 는 100 - idle, 나머지는 해당 mode 시간 비율이다.
 * 참고 설계의 vCenter 지표(Usage MHz·Readiness 등)는 물리 노드에서 수집할 수 없어 mode 기준으로 확정했다.
 */
public enum CpuMetric {
    CPU_USED(null),
    CPU_IDLE("idle"),
    CPU_USER("user"),
    CPU_SYSTEM("system"),
    CPU_IO_WAIT("iowait"),
    CPU_NICE("nice"),
    CPU_IRQ("irq"),
    CPU_SOFT_IRQ("softirq"),
    CPU_STEAL("steal");

    private final String mode;

    CpuMetric(String mode) {
        this.mode = mode;
    }

    /** {@code node_cpu_seconds_total} 의 mode 라벨 값 — CPU_USED 는 null(100 - idle 로 계산) */
    public String mode() {
        return mode;
    }

    public String unit() {
        return "%";
    }
}

package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu;

/**
 * VM CPU 카테고리 모니터링 탭의 차트 지표 9종 — CPU mode 별 사용률(%).
 * 노드 CPU(CpuMetric)와 지표 세트가 다르다 (참고 설계 기준, AIMS 화면 확정 시 조정 가능).
 * 전부 node_exporter node_cpu_seconds_total 의 mode 레이블로 수집 가능하다.
 */
public enum VmCpuMetric {
    CPU_USED("%"),
    CPU_IDLE("%"),
    CPU_USER("%"),
    CPU_SYSTEM("%"),
    CPU_IO_WAIT("%"),
    CPU_NICE("%"),
    CPU_IRQ("%"),
    CPU_SOFT_IRQ("%"),
    CPU_STEAL("%");

    private final String unit;

    VmCpuMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}

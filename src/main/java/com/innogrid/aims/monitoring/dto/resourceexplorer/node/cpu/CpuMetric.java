package com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu;

/**
 * CPU 카테고리 모니터링 탭의 차트 지표 7종.
 * READINESS 는 하이퍼바이저(vCenter) 전용 지표로 물리 노드(node_exporter)에서는 수집 불가 —
 * 지표 세트 확정 전까지 미지원 지표는 501 또는 빈 시리즈로 처리한다.
 */
public enum CpuMetric {
    CPU_USAGE("%"),
    CPU_UTILIZATION("%"),
    CPU_USAGE_MHZ("MHz"),
    CPU_USAGE_TIME("ms"),
    CPU_IDLE_TIME("ms"),
    CPU_WAIT_TIME("ms"),
    CPU_READINESS("%");

    private final String unit;

    CpuMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}

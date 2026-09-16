package com.innogrid.aims.monitoring.dto.resourceexplorer.node.system;

/**
 * System 카테고리 모니터링 탭의 차트 지표 5종 — node_exporter loadavg({@code node_load1/5/15})와
 * stat({@code node_context_switches_total, node_intr_total}) 카운터의 rate.
 * 부하 평균은 단위가 없고(실행 대기 프로세스 수), 컨텍스트 스위치·인터럽트는 초당 건수다.
 */
public enum SystemMetric {
    LOAD_1(""),
    LOAD_5(""),
    LOAD_15(""),
    CONTEXT_SWITCHES("/s"),
    INTERRUPTS("/s");

    private final String unit;

    SystemMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}

package com.innogrid.aims.monitoring.dto.resourceexplorer.node.process;

/**
 * Process 카테고리 모니터링 탭의 차트 지표 5종 — node_exporter stat({@code node_procs_running, node_procs_blocked, node_forks_total})과
 * processes 콜렉터({@code node_processes_pids, node_processes_threads}).
 * PROCESSES_TOTAL·THREADS 는 {@code --collector.processes} 가 켜진 노드에서만 값이 있다(docs/06 §5.4 — PID 별 목록은 제공하지 않는다).
 */
public enum ProcessMetric {
    PROCS_RUNNING("count"),
    PROCS_BLOCKED("count"),
    PROCESSES_TOTAL("count"),
    THREADS("count"),
    FORKS_RATE("/s");

    private final String unit;

    ProcessMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}

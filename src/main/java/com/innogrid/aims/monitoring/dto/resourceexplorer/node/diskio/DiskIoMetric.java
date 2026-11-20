package com.innogrid.aims.monitoring.dto.resourceexplorer.node.diskio;

/**
 * Disk I/O 카테고리 모니터링 탭의 차트 지표 7종 — node_exporter {@code node_disk_*} 카운터의 rate 기반.
 * 값은 원시 단위(B/s, IOPS, ms, %)로 내려주고 표기 변환은 프론트가 담당한다.
 * 지연(ms)은 {@code rate(*_time_seconds_total) / rate(*_completed_total) * 1000}, 사용률은 {@code rate(io_time_seconds_total) * 100}.
 */
public enum DiskIoMetric {
    DISK_READ_BYTES("B/s"),
    DISK_WRITE_BYTES("B/s"),
    DISK_READ_IOPS("IOPS"),
    DISK_WRITE_IOPS("IOPS"),
    DISK_READ_LATENCY("ms"),
    DISK_WRITE_LATENCY("ms"),
    DISK_UTILIZATION("%");

    private final String unit;

    DiskIoMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}

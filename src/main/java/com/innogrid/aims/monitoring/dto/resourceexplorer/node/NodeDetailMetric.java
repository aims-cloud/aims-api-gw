package com.innogrid.aims.monitoring.dto.resourceexplorer.node;

/** 노드 상세 개요 탭의 모니터링 요약 차트 지표 6종. */
public enum NodeDetailMetric {
    CPU("%"),
    MEMORY("%"),
    FILE_SYSTEM("%"),
    NETWORK("B/s"),
    GPU("%"),
    VRAM("%");

    private final String unit;

    NodeDetailMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}


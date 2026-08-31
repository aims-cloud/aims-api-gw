package com.innogrid.aims.monitoring.dto.resourceexplorer.node.memory;

/**
 * Memory 카테고리 모니터링 탭의 차트 지표 5종.
 * Free 는 MemAvailable(캐시 회수 가능분 포함) 기준 — Used + Free = Total 이 성립하도록
 * Used 도 MemTotal - MemAvailable 로 계산한다.
 */
public enum MemoryMetric {
    MEMORY_USED("GB"),
    MEMORY_USED_PERCENT("%"),
    MEMORY_FREE("GB"),
    MEMORY_FREE_PERCENT("%"),
    MEMORY_TOTAL("GB");

    private final String unit;

    MemoryMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}

package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.memory;

/**
 * VM Memory 카테고리 모니터링 탭의 차트 지표 9종.
 * 노드 Memory(5종)와 달리 Free(MemFree, 순수 미사용)와 Available(MemAvailable, 캐시 회수 가능분 포함)을
 * 구분하고 Cached/Buffered/Shared 를 추가로 제공한다 — 전부 node_exporter node_memory_* 로 수집 가능.
 */
public enum VmMemoryMetric {
    MEMORY_USED("GB"),
    MEMORY_USED_PERCENT("%"),
    MEMORY_FREE("GB"),                  // MemFree
    MEMORY_AVAILABLE("GB"),             // MemAvailable
    MEMORY_AVAILABLE_PERCENT("%"),
    MEMORY_TOTAL("GB"),
    MEMORY_CACHED("GB"),
    MEMORY_BUFFERED("GB"),
    MEMORY_SHARED("GB");

    private final String unit;

    VmMemoryMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}

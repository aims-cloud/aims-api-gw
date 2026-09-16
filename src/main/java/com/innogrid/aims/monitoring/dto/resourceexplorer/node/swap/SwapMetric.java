package com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap;

/**
 * Swap 카테고리 모니터링 탭의 차트 지표 6종 — node_exporter {@code node_memory_Swap*_bytes}(용량)와
 * {@code node_vmstat_pswpin/pswpout}(스왑 인·아웃 페이지 카운터의 rate).
 * Used 는 SwapTotal - SwapFree. 스왑이 없는 호스트(SwapTotal 0)는 사용률이 정의되지 않아 빈 시리즈다.
 */
public enum SwapMetric {
    SWAP_USED("GB"),
    SWAP_USED_PERCENT("%"),
    SWAP_FREE("GB"),
    SWAP_TOTAL("GB"),
    SWAP_IN("pages/s"),
    SWAP_OUT("pages/s");

    private final String unit;

    SwapMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}

package com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace;

/** 엔드포인트 위젯 1행 — 메서드별 상위 엔드포인트와 요청 수. */
public record TraceEndpointStatResponse(
        String method,
        String endpoint,
        long count
) {
}

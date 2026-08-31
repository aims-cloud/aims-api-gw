package com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace;

import java.util.List;

/** 메서드 분포 도넛 — 총 요청 수와 HTTP 메서드별 건수. */
public record TraceMethodDistributionResponse(
        long totalCount,
        List<MethodCount> methods
) {
    public record MethodCount(String method, long count) {}

    public TraceMethodDistributionResponse {
        methods = methods == null ? List.of() : List.copyOf(methods);
    }
}

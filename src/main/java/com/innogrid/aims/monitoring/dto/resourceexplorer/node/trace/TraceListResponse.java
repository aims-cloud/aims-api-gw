package com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 트레이스 목록 — 시간 역순. 최근 트레이스 위젯과 트레이스 상세 페이지 목록에 공용.
 * 상태 필터는 클래스(2XX/4XX/5XX) 단위지만 행에는 정확한 상태 코드가 내려간다.
 */
public record TraceListResponse(
        long totalCount,
        List<Item> traces
) {
    public record Item(String traceId, OffsetDateTime timestamp, String method,
                       String endpoint, int statusCode, double durationMs) {}

    public TraceListResponse {
        traces = traces == null ? List.of() : List.copyOf(traces);
    }
}

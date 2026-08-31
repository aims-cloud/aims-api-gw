package com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 트레이스 상세 패널 — 요약 카드 6종(루트/시작/총 소요/스팬 수/상태/포트)과 스팬 워터폴.
 * 관련 로그는 traces/{traceId}/logs 로 별도 조회한다.
 */
public record TraceDetailResponse(
        String traceId,
        String rootService,
        OffsetDateTime startedAt,
        double totalDurationMs,
        int spanCount,
        int statusCode,
        Integer port,
        List<Span> spans
) {
    /**
     * 스팬 1개 — 워터폴 바(시작 오프셋·소요)와 클릭 상세(전체 대비 %·부모)에 사용한다.
     * startOffsetMs 는 트레이스 시작 기준 상대 시각이다.
     */
    public record Span(String spanId, String name, String parentSpanId, String parentName,
                       double startOffsetMs, double durationMs, double percentOfTotal) {}

    public TraceDetailResponse {
        spans = spans == null ? List.of() : List.copyOf(spans);
    }
}

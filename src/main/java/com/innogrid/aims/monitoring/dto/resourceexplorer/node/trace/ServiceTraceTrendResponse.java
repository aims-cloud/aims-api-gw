package com.innogrid.aims.monitoring.dto.resourceexplorer.node.trace;

import java.util.List;

/**
 * 요청량 추이 막대 차트 — 버킷별 요청 수.
 * 차트 드래그 구간 선택("선택된 기간 … 요청 수 N건")은 프론트가 버킷 합산으로 계산한다.
 */
public record ServiceTraceTrendResponse(
        long from,
        long to,
        String interval,
        List<Bucket> buckets
) {
    /** 시간 버킷 1개 — timestamp 는 버킷 시작(Unix 초) */
    public record Bucket(long timestamp, long requestCount) {}
}

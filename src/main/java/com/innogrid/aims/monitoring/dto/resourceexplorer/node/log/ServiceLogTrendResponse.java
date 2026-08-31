package com.innogrid.aims.monitoring.dto.resourceexplorer.node.log;

import java.util.List;

/**
 * 로그 발생 추이 스택 차트 — 버킷마다 레벨별 건수를 담는다.
 * 로그 서브탭과 로그 상세 페이지(분할: 파일별 호출 / 병합: files 복수 호출)에 공용.
 */
public record ServiceLogTrendResponse(
        long from,
        long to,
        String interval,
        List<Bucket> buckets
) {
    /** 시간 버킷 1개 — timestamp 는 버킷 시작(Unix 초) */
    public record Bucket(long timestamp, long infoCount, long warningCount,
                         long debugCount, long errorCount) {}
}

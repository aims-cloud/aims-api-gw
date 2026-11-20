package com.innogrid.aims.monitoring.dto.resourceexplorer.node.log;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 로그 목록/검색 응답 — 시간 역순, limit 만큼 반환하고 totalCount 로 전체 건수를 표기한다.
 * "최근 에러" 위젯은 levels=ERROR 필터 + limit 로 같은 응답을 사용한다.
 */
public record ServiceLogListResponse(
        long totalCount,
        List<Entry> entries
) {
    /** 로그 1건 — message 전문은 로그 상세 팝오버에 그대로 사용한다 */
    public record Entry(OffsetDateTime timestamp, ServiceLogLevel level,
                        String file, String message) {}
}

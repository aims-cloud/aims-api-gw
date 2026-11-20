package com.innogrid.aims.monitoring.dto.resourceexplorer.node;

import java.time.LocalDate;

/**
 * 노드 상세 자원 사용 현황 탭의 테이블 1행 — 일자별 일간 최대치.
 * 값(Double/Long)이 null 이면 해당 일자 미수집("-")이다.
 */
public record NodeDailyUsageResponse(
        LocalDate date,
        Double maxCpuUtilization,
        Double maxMemoryUtilization,
        Double maxFileSystemUtilization,
        Long maxVmCount
) {
}

package com.innogrid.aims.monitoring.dto.alarm.alarmPolicy;

import java.util.List;

/** 조건 설정의 메트릭 카탈로그 — 유형 드롭다운과 유형별 메트릭 드롭다운을 채운다. */
public record AlarmMetricCatalogResponse(
        String metricType,              // 예: CPU
        List<MetricOption> metrics
) {
    /** 메트릭 옵션 1개 — 선택 시 미리보기 차트는 자원 탐색기 trend API 를 재사용한다 */
    public record MetricOption(String metric, String displayName, String unit) {}

    public AlarmMetricCatalogResponse {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }
}

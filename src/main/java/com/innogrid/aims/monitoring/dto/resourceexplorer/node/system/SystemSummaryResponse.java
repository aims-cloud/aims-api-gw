package com.innogrid.aims.monitoring.dto.resourceexplorer.node.system;

/**
 * System 카테고리 개요 탭의 KPI 카드 — 가동 시간(초), 부하 평균 1/5/15분, 코어 수, 컨텍스트 스위치·인터럽트(최근 5분 rate, /s).
 * 미수집이면 null — 화면에는 "-" 로 표시한다. 가동 시간은 지금 - node_boot_time_seconds 이며 프론트가 일/시간으로 표기한다.
 */
public record SystemSummaryResponse(
        Long uptimeSeconds,
        Double load1,
        Double load5,
        Double load15,
        Integer coreCount,
        Double contextSwitchesPerSec,
        Double interruptsPerSec
) {
}

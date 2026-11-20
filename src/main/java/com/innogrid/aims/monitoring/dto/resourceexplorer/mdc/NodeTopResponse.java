package com.innogrid.aims.monitoring.dto.resourceexplorer.mdc;

/**
 * 노드 Top-N 항목. value 는 요청한 metric 의 값이고,
 * 세 지표를 모두 담아 프론트가 토글 시 보조 지표를 재조회 없이 표시한다.
 * 요청 지표가 미수집인 노드는 목록에서 제외된다 — 남는 슬롯은 화면이 "미수집"으로 채운다.
 * 보조 지표(Double)가 null 이면 해당 지표만 미수집("-")이다.
 * File system 토글은 사용률 아래에 Free/Total 용량을 함께 표시한다.
 */
public record NodeTopResponse(
        String nodeId,
        String name,
        String alias,
        NodeStatus status,
        double value,
        Double cpuUtilization,
        Double memoryUtilization,
        Double fileSystemUtilization,
        Double fileSystemFreeGb,
        Double fileSystemTotalGb
) {
}

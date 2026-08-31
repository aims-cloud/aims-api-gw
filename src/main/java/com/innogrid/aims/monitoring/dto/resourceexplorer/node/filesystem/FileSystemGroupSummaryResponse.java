package com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem;

/**
 * File system 그룹 개요 탭의 KPI 카드 8종.
 * maxUsedPercent / maxUsedGb 카드는 해당 값이 나온 마운트 경로를 보조 표기하므로
 * (두 값의 마운트가 다를 수 있어) 경로 필드를 각각 내려준다.
 * maxInodeUsagePercent 는 수집 불가 파일시스템만 있으면 null — 화면에는 "-" 로 표시한다.
 */
public record FileSystemGroupSummaryResponse(
        int totalMountCount,
        int nearFullCount,              // 사용률이 임계치를 넘은 마운트 수 (임계 기준 TODO:: 확정 필요)
        double maxUsedPercent,
        String maxUsedPercentMountPath,
        double maxUsedGb,
        String maxUsedGbMountPath,
        double totalSizeGb,
        double totalUsedGb,
        double totalFreeGb,
        Double maxInodeUsagePercent
) {
}

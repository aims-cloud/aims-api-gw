package com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem;

/**
 * File system 그룹 개요 탭의 KPI 카드 8종.
 * maxUsedPercent / maxUsedGb 카드는 해당 값이 나온 마운트 경로를 보조 표기하므로
 * (두 값의 마운트가 다를 수 있어) 경로 필드를 각각 내려준다. 마운트가 하나도 없으면 값은 0, 경로는 null 이다.
 * maxInodeUsagePercent 는 inode 를 보고하지 않는 파일시스템만 있으면 null — 화면에는 "-" 로 표시한다.
 */
public record FileSystemGroupSummaryResponse(
        int totalMountCount,
        int nearFullCount,              // 사용률이 90% 이상인 마운트 수 (ResourceExplorerNodeFileSystemService.NEAR_FULL_PERCENT)
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

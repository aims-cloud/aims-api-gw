package com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem;

/**
 * File system 그룹 개요 탭의 목록 1행.
 * inodeUsagePercent 는 수집 불가 시 null — 화면에는 "-" 로 표시한다.
 */
public record FileSystemMountListItemResponse(
        String mountPath,
        String fsType,
        double totalGb,
        double usedPercent,
        double usedGb,
        double freeGb,
        Double inodeUsagePercent
) {
}

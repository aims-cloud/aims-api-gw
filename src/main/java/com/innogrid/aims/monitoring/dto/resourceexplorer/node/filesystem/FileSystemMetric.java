package com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem;

/**
 * File system 카테고리 모니터링 탭의 차트 지표.
 * TODO:: 모니터링 탭 화면 설계 확정 시 지표 세트 조정 (Memory 패턴 기준 잠정 6종)
 */
public enum FileSystemMetric {
    FS_USED("GB"),
    FS_USED_PERCENT("%"),
    FS_FREE("GB"),
    FS_FREE_PERCENT("%"),
    FS_TOTAL("GB"),
    FS_INODE_USAGE_PERCENT("%");

    private final String unit;

    FileSystemMetric(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }
}

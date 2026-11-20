package com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem;

/**
 * File system 카테고리 모니터링 탭의 차트 지표 6종 — Memory 패턴 기준.
 * Used 는 size - avail(예약 블록 제외), Free 는 avail 이라 Used + Free = Total 이 성립한다.
 * INODE_USAGE_PERCENT 는 node_filesystem_files / files_free 기준이며 inode 를 보고하지 않는 파일시스템은 빈 시리즈다.
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

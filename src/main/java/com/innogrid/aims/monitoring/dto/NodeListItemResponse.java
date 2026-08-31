package com.innogrid.aims.monitoring.dto;

/** 노드 목록 테이블 1행 — 이름/별칭/상태/IP/CPU/Memory(사용·전체)/File system(사용·전체). */
public record NodeListItemResponse(
        String nodeId,
        String name,
        String alias,
        NodeStatus status,
        String ipAddress,
        double cpuUtilization,
        double memoryUtilization,
        double memoryUsedGb,
        double memoryTotalGb,
        double fileSystemUtilization,
        double fileSystemUsedGb,
        double fileSystemTotalGb
) {
}

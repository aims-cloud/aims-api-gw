package com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem;

import java.time.OffsetDateTime;

import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;

/** 개별 마운트 개요 탭의 File System 정보 표. */
public record FileSystemMountInfoResponse(
        String mountPath,
        String fsType,
        MonitoringStatus collectStatus, // 표본이 있는 마운트만 오므로 COLLECTING
        OffsetDateTime lastCollectedAt
) {
}

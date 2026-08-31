package com.innogrid.aims.monitoring.dto.resourceexplorer.node.filesystem;

import java.time.OffsetDateTime;

/** 개별 마운트 개요 탭의 File System 정보 표. */
public record FileSystemMountInfoResponse(
        String mountPath,
        String fsType,
        String collectStatus,           // TODO:: 수집 상태 값 체계 확정 후 enum 전환 (CPU 목록과 공통화)
        OffsetDateTime lastCollectedAt
) {
}

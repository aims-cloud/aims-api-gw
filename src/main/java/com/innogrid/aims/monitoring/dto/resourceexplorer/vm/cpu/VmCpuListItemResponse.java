package com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu;

import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.MonitoringStatus;

/**
 * VM CPU 카테고리 개요 탭의 CPU 목록 1행 — 노드 CPU 목록의 축소판
 * (CPU ID·기본 클럭·마지막 수집 컬럼 없음).
 */
public record VmCpuListItemResponse(
        int socket,
        String vendor,
        String model,
        int coreCount,
        int threadCount,
        MonitoringStatus collectStatus  // 행이 있으면 COLLECTING
) {
}

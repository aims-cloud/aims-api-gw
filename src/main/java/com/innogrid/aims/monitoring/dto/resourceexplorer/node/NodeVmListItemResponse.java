package com.innogrid.aims.monitoring.dto.resourceexplorer.node;

import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeStatus;

/**
 * 노드 상세 가상머신 탭의 목록 1행.
 * TODO:: 가상머신 탭 화면 설계 확정 시 컬럼 재정의
 */
public record NodeVmListItemResponse(
        String vmId,
        String name,
        NodeStatus status,
        String ipAddress,
        double cpuUtilization,
        double memoryUtilization
) {
}


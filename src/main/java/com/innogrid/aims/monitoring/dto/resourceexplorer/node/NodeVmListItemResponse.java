package com.innogrid.aims.monitoring.dto.resourceexplorer.node;

import java.util.List;

import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeStatus;

/**
 * 노드 상세 가상머신 탭의 목록 1행.
 * IP 는 VM 당 여러 개일 수 있고, 사용률(Double)이 null 이면 미수집("-")이다.
 * TODO:: 가상머신 탭 화면 설계 확정 시 컬럼 재정의
 */
public record NodeVmListItemResponse(
        String vmId,
        String name,
        String alias,
        NodeStatus status,
        List<String> ipAddresses,
        Double cpuUtilization,
        Double memoryUtilization,
        Double diskUtilization
) {
    public NodeVmListItemResponse {
        ipAddresses = ipAddresses == null ? List.of() : List.copyOf(ipAddresses);
    }
}


package com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup;

import java.util.List;

import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;

/**
 * NodeGroup 패널 VM 목록 테이블 1행 — Tumblebug Node 1개.
 * <ul>
 *   <li>{@code vmId}: 트리 resourceId 와 같은 {@code TB:{ns}/{infra}/{node}}</li>
 *   <li>{@code status}: 필터·색상용 4종 버킷, {@code tumblebugStatus}: Tumblebug 상태값 원문 (docs/06 §6.1)</li>
 *   <li>{@code ipAddresses}: Tumblebug 이 기록한 publicIP·privateIP (없는 값은 제외)</li>
 *   <li>사용률(Double)이 null 이면 미수집("-") — VM 안 node_exporter 가 tb_* 라벨로 등록되지 않은 경우</li>
 * </ul>
 */
public record VmListItemResponse(
        String vmId,
        String name,
        NodeStatus status,
        String tumblebugStatus,
        String provider,
        List<String> ipAddresses,
        Double cpuUtilization,
        Double memoryUtilization,
        Double fileSystemUtilization,
        Double fileSystemFreeGb,
        Double fileSystemTotalGb
) {
    public VmListItemResponse {
        ipAddresses = ipAddresses == null ? List.of() : List.copyOf(ipAddresses);
    }
}

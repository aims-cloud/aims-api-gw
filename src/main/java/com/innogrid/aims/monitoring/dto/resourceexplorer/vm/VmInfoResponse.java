package com.innogrid.aims.monitoring.dto.resourceexplorer.vm;

import java.util.List;

/**
 * VM 상세 개요 탭의 VM 정보 표 — Tumblebug NodeInfo 기준 (docs/06 §4.2, §9.5).
 * <ul>
 *   <li>{@code vmId}: 트리 resourceId 와 같은 {@code TB:{ns}/{infra}/{node}}</li>
 *   <li>{@code tumblebugStatus}: Tumblebug 상태값 원문 (§6.1 — 상세는 원문 노출)</li>
 *   <li>{@code provider}: 우상단 배지. connectionName·region·zone 은 §9.5 에 따라 상세에서만 제공</li>
 *   <li>{@code cspResourceId}: CSP 가 부여한 실제 VM ID (Tumblebug 내부 ID 와 구분)</li>
 * </ul>
 */
public record VmInfoResponse(
        String vmId,
        String name,
        String nsId,
        String infraId,
        String nodeGroupId,             // 없으면 null (미분류)
        String tumblebugStatus,
        String provider,                // 예: openstack, aws
        String connectionName,          // 예: openstack-regionone
        String region,
        String zone,
        String cspResourceId,
        List<String> ipAddresses,       // publicIP, privateIP 순 (없는 값은 제외)
        String specName,                // cspSpecName, 예: m1.small
        Integer vCpu,
        Double memoryGiB,
        Integer rootDiskGb,
        String rootDiskType,
        String osType,                  // image.osType, 예: Ubuntu 24.04
        String createdTime              // Tumblebug 기록 시각 원문
) {
    public VmInfoResponse {
        ipAddresses = ipAddresses == null ? List.of() : List.copyOf(ipAddresses);
    }
}

package com.innogrid.aims.monitoring.dto.resourceexplorer.node;

/**
 * 노드 상세 개요 탭의 노드 정보 표.
 * 자원 탐색기 패키지 자체 원장 기준으로 정의하며 다른 도메인 엔티티를 재사용하지 않는다.
 * provider / platformType 은 표 우상단 배지(프로바이더 로고, IaaS 등) 노출에 사용한다.
 */
public record NodeInfoResponse(
        String nodeId,
        String name,
        String ip,
        long uptimeDays,
        String type,
        int vmCount,
        int cpuAllocatedCores,
        double memoryAllocatedGb,
        String storage,
        String provider,        // 예: OPENSTACK
        String platformType     // 예: IaaS
) {
}


package com.innogrid.aims.monitoring.model;

import java.util.List;

/**
 * 자원 탐색기 트리 구성용 자원 계층 원본 데이터 묶음.
 * 역할(Role)은 auth 도메인의 사용자 역할(ADMIN/USER 등)과 동일한 ID 체계를 쓴다 —
 * 트리에는 현재 사용자 토큰에 부여된 역할만 노출된다.
 */
public record ResourceExplorerTreeDataset(
        List<RoleInfo> roles,
        List<NodeInfo> nodes,
        List<VmInfo> vms
) {
    /** 역할 1건 — roleId 는 토큰 roles 값(예: ADMIN)과 일치. 관리자/멤버 수는 전체 자원·역할 패널의 역할 테이블에 쓴다. */
    public record RoleInfo(
            String roleId,
            String roleName,
            String managerName,
            long memberCount
    ) {}

    /** 물리 노드 1대 — gpuIds 가 있으면 트리에 GPU 그룹/장치 하위 항목이 생긴다.
     *  IP 는 노드당 여러 개(관리망/서비스망)일 수 있다.
     *  spec 은 노드 상세 정보 표의 정적 정보, metrics 는 수집 지표 — metrics 가 null 이면 전체 미수집이다. */
    public record NodeInfo(
            String nodeId,
            String name,
            String alias,
            String roleId,
            String deploymentId,
            String status,
            List<String> ipAddresses,
            NodeSpec spec,
            NodeMetrics metrics,
            List<String> gpuIds
    ) {
        public NodeInfo {
            ipAddresses = ipAddresses == null ? List.of() : List.copyOf(ipAddresses);
            gpuIds = gpuIds == null ? List.of() : List.copyOf(gpuIds);
        }
    }

    /** 노드 정적 스펙 — 노드 상세 개요 탭 정보 표와 우상단 배지(provider/platformType)에 쓴다.
     *  cpuCoreCount 는 탑재 코어 수(주요 지표 CPU 카드의 보조 표기), cpuAllocatedCores 는 할당량(정보 표)이다. */
    public record NodeSpec(
            String type,                // 예: Hypervisor
            String provider,            // 예: OPENSTACK
            String platformType,        // 예: IaaS
            long uptimeDays,
            int cpuCoreCount,
            int cpuAllocatedCores,
            double memoryAllocatedGb,
            String storage              // 예: Ceph 10TB
    ) {}

    /** 노드 수집 지표 — 개별 값(Double)이 null 이면 해당 지표만 미수집("-")이다 */
    public record NodeMetrics(
            Double cpuUtilization,
            Double memoryUsedGb,
            Double memoryTotalGb,
            Double fileSystemUsedGb,
            Double fileSystemTotalGb,
            Double gpuUtilization,
            Double vramUsedGb,
            Double vramTotalGb,
            Double networkReceiveBytesPerSec,
            Double networkTransmitBytesPerSec
    ) {}

    /** 가상 머신 1대 — nodeId 는 실행 중인 물리 노드(노드 상세 가상머신 탭의 매핑 기준).
     *  IP 는 VM 당 여러 개일 수 있고, 사용률(Double)이 null 이면 미수집("-")이다. */
    public record VmInfo(
            String vmId,
            String name,
            String alias,
            String roleId,
            String deploymentId,
            String nodeId,
            String status,
            List<String> ipAddresses,
            Double cpuUtilization,
            Double memoryUtilization,
            Double diskUtilization,
            List<String> gpuIds
    ) {
        public VmInfo {
            ipAddresses = ipAddresses == null ? List.of() : List.copyOf(ipAddresses);
            gpuIds = gpuIds == null ? List.of() : List.copyOf(gpuIds);
        }
    }
}

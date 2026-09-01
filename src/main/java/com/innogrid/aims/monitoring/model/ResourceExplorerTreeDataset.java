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
     *  별칭/IP/사용률은 노드그룹 패널의 노드 목록·Top-N 에 쓴다.
     *  IP 는 노드당 여러 개(관리망/서비스망)일 수 있고, 메트릭(Double)이 null 이면 미수집("-")이다. */
    public record NodeInfo(
            String nodeId,
            String name,
            String alias,
            String roleId,
            String deploymentId,
            String status,
            List<String> ipAddresses,
            Double cpuUtilization,
            Double memoryUsedGb,
            Double memoryTotalGb,
            Double fileSystemUsedGb,
            Double fileSystemTotalGb,
            List<String> gpuIds
    ) {
        public NodeInfo {
            ipAddresses = ipAddresses == null ? List.of() : List.copyOf(ipAddresses);
            gpuIds = gpuIds == null ? List.of() : List.copyOf(gpuIds);
        }
    }

    /** 가상 머신 1대 */
    public record VmInfo(
            String vmId,
            String name,
            String roleId,
            String deploymentId,
            String status,
            List<String> gpuIds
    ) {
        public VmInfo {
            gpuIds = gpuIds == null ? List.of() : List.copyOf(gpuIds);
        }
    }
}

package com.innogrid.aims.openstack.dto;

import java.util.List;

/**
 * 통합 토폴로지 트리 응답 DTO 모음.
 * Swagger 스키마에 필드 구조가 그대로 노출되도록 Map 대신 record 로 정의한다.
 */
public final class TopologyDtos {

    private TopologyDtos() {}

    /** 배포영역 드롭다운 항목. */
    public record DeployZone(String name, String provider, boolean configured) {}

    /** 토폴로지 트리 최상위. */
    public record TopologyTree(String deployZone, String provider,
                               NetworkLayer networkLayer, ComputeLayer computeLayer) {}

    // ── 네트워크 레이어 ─────────────────────────────────────────

    public record NetworkLayer(List<NetworkProject> projects) {}

    /**
     * 네트워크 소유 프로젝트. 그룹 3종은 배열로 분리:
     * routersWithoutGateway(외부 게이트웨이 없는 라우터), unconnectedNetworks(라우터 미연결 네트워크).
     */
    public record NetworkProject(String id, String name,
                                 List<RouterNode> routers,
                                 List<RouterNode> routersWithoutGateway,
                                 List<NetworkNode> unconnectedNetworks,
                                 List<NetworkNode> externalNetworks) {}

    public record RouterNode(String id, String name, String status, Boolean adminStateUp,
                             int staticRouteCount, ExternalGatewayRef externalGateway,
                             List<NetworkNode> networks) {}

    /** 라우터 외부 게이트웨이 요약 (null 이면 게이트웨이 없음). */
    public record ExternalGatewayRef(String networkId, String networkName, String ipAddress) {}

    public record NetworkNode(String id, String name, String status, Boolean adminStateUp,
                              Integer mtu, Boolean shared, Boolean external,
                              List<SubnetNode> subnets) {}

    public record SubnetNode(String id, String name, String cidr, String gatewayIp,
                             List<PortRow> ports) {}

    /** 서브넷 안의 포트 행 = VM 연결 정보. */
    public record PortRow(String vmId, String vmName, String status,
                          String ipAddress, String macAddress, String portId) {}

    // ── 컴퓨트 레이어 ───────────────────────────────────────────

    public record ComputeLayer(List<ComputeZone> zones, List<ProjectRef> projectsWithoutVm) {}

    public record ComputeZone(String name, Boolean available, List<ComputeHost> hosts) {}

    public record ComputeHost(String name, String state, String status, List<ComputeProject> projects) {}

    public record ComputeProject(String id, String name, List<VmSummary> vms) {}

    public record VmSummary(String id, String name, String status) {}

    /** VM 없는 프로젝트 그룹 항목. */
    public record ProjectRef(String id, String name) {}
}

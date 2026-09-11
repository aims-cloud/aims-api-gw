package com.innogrid.aims.openstack.dto;

import java.util.List;

/**
 * 통합 토폴로지 노드 클릭 상세 응답 DTO 모음.
 * 탭클라우드잇 상세 패널 항목에 1:1 대응한다.
 */
public final class TopologyDetailDtos {

    private TopologyDetailDtos() {}

    // ── 라우터 상세 ─────────────────────────────────────────────

    public record RouterDetail(String id, String name, String status, Boolean adminStateUp,
                               Boolean distributed, String createdAt,
                               TopologyDtos.ExternalGatewayRef externalGateway,
                               List<RouterInterface> interfaces, List<StaticRoute> staticRoutes) {}

    public record RouterInterface(String ip, String networkId, String networkName,
                                  String subnetId, String subnetName) {}

    public record StaticRoute(String destination, String nexthop) {}

    // ── 네트워크 상세 ───────────────────────────────────────────

    public record NetworkDetail(String id, String name, String status, Boolean adminStateUp,
                                String networkType, Integer mtu, Boolean shared, Boolean external,
                                List<SubnetDetail> subnets, List<PortDetail> ports) {}

    public record SubnetDetail(String id, String name, String cidr, Integer ipVersion,
                               String gatewayIp, Boolean enableDhcp, int portCount) {}

    public record PortDetail(String subnetId, String subnetName, String vmId, String vmName,
                             String ipAddress, String macAddress, String status, Boolean adminStateUp) {}

    // ── 외부 게이트웨이 포트 상세 ───────────────────────────────

    public record GatewayPortDetail(String id, String name, String ipAddress,
                                    String status, String macAddress) {}

    // ── 가상머신 상세 ───────────────────────────────────────────

    public record ServerDetail(String id, String name, String status, String powerState,
                               Integer vcpus, Integer ramMb, Integer diskGb,
                               String imageName, String flavorName,
                               List<ServerIp> ips, List<SecurityGroupRule> securityGroupRules) {}

    public record ServerIp(String networkName, String ipAddress) {}

    public record SecurityGroupRule(String securityGroup, String direction, String etherType,
                                    String protocol, String portRange,
                                    String remoteIpPrefix, String remoteGroup) {}

    // ── 물리노드·하이퍼바이저 상세 ─────────────────────────────

    public record HypervisorDetail(String name, String hostIp, String availabilityZone, String service,
                                   String state, String status, String hypervisorType,
                                   Integer vcpusUsed, Integer vcpus,
                                   Long memoryUsedMb, Long memoryMb,
                                   Long diskUsedGb, Long diskGb, Integer runningVms) {}

    // ── 프로젝트 상세 ───────────────────────────────────────────

    public record ProjectDetail(String id, String name, String domain, Boolean enabled,
                                int runningVms, int totalVms, String createdAt, List<ProjectVm> vms) {}

    public record ProjectVm(String id, String name, String status, String powerState,
                            Integer vcpus, Integer ramMb, Integer diskGb, List<ServerIp> ips) {}
}

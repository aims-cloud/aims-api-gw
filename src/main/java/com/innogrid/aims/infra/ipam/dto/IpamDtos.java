package com.innogrid.aims.infra.ipam.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 네트워크 IPAM(구역/서브넷/IP주소) 응답·요청 DTO 모음.
 */
public final class IpamDtos {

    private IpamDtos() {}

    // ── 네트워크 구역 ───────────────────────────────────────────

    public record ZoneSummary(Long id, String name, String description,
                              String createdBy, String updatedBy,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt,
                              int subnetCount, int interfaceCount) {}

    public record ZoneRequest(String name, String description) {}

    public record ZoneInterface(Long id, String deployZone, String networkName,
                                String openstackNetworkId, String datacenter) {}

    /** 인터페이스 관리 후보 (OpenStack 네트워크). */
    public record InterfaceCandidate(String deployZone, String networkName,
                                     String openstackNetworkId, String datacenter) {}

    public record SaveInterfacesRequest(List<InterfaceRef> interfaces) {}

    public record InterfaceRef(String deployZone, String networkName,
                               String openstackNetworkId, String datacenter) {}

    // ── 서브넷 ──────────────────────────────────────────────────

    public record SubnetSummary(Long id, Long zoneId, String zoneName, String cidr,
                                String ipRangeFirst, String ipRangeLast, long totalHosts,
                                long usedCount, double usagePercent, String gateway,
                                String description, String createdBy, String updatedBy,
                                OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record SubnetRequest(Long zoneId, String cidr, String gateway, String description) {}

    // ── IP 주소 ─────────────────────────────────────────────────

    public record IpRow(Long id, Long zoneId, String zoneName, Long subnetId, String cidr,
                        String ipAddress, String status, String resourceType, String resourceId,
                        String resourceName, String alias, String portGroup, String macAddress,
                        String description, String createdBy, String updatedBy,
                        OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    /** IP 예약 생성 (단일/범위). 범위면 ipEnd 지정. */
    public record ReserveIpRequest(Long subnetId, String ipStart, String ipEnd, String description) {}

    public record UpdateIpRequest(String alias, String description) {}

    // ── 행위 이력 ───────────────────────────────────────────────

    public record HistoryRow(Long id, String performedBy, String clientIp, String target,
                             String action, String result, String detail,
                             OffsetDateTime startedAt, OffsetDateTime completedAt) {}
}

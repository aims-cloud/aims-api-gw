package com.innogrid.aims.infra.ipam.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.IpRow;
import com.innogrid.aims.infra.ipam.entity.IpAddress;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Action;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Result;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Target;
import com.innogrid.aims.infra.ipam.entity.NetworkSubnet;
import com.innogrid.aims.infra.ipam.entity.NetworkZone;
import com.innogrid.aims.infra.ipam.repository.IpAddressRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkSubnetRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkZoneRepository;
import com.innogrid.aims.openstack.OpenStackNetworkCatalog;
import com.innogrid.aims.openstack.OpenStackNetworkCatalog.AllocatedIp;

/**
 * IP 주소 서비스 (IPAM Phase 3).
 * - 할당됨: OpenStack 포트 동기화 (서브넷 CIDR에 속한 실제 IP)
 * - 예약됨: 사용자 수동 생성 (단일/범위)
 * 별칭·설명은 사용자 오버레이로 동기화 시 보존한다.
 */
@Service
public class IpAddressService {

    private final IpAddressRepository ipRepository;
    private final NetworkSubnetRepository subnetRepository;
    private final NetworkZoneRepository zoneRepository;
    private final OpenStackNetworkCatalog networkCatalog;
    private final NetworkAuditService audit;

    public IpAddressService(IpAddressRepository ipRepository,
                            NetworkSubnetRepository subnetRepository,
                            NetworkZoneRepository zoneRepository,
                            OpenStackNetworkCatalog networkCatalog,
                            NetworkAuditService audit) {
        this.ipRepository = ipRepository;
        this.subnetRepository = subnetRepository;
        this.zoneRepository = zoneRepository;
        this.networkCatalog = networkCatalog;
        this.audit = audit;
    }

    // ── 조회 ────────────────────────────────────────────────────

    /** 서브넷의 IP 목록 (동기화 후). */
    @Transactional
    public List<IpRow> listBySubnet(Long subnetId) {
        NetworkSubnet subnet = findSubnet(subnetId);
        syncSubnet(subnet, allocatedIps());
        return rows(ipRepository.findBySubnetId(subnetId));
    }

    /** 전체 IP 목록 (구역/서브넷/상태/검색 필터). 전체 동기화 후 조회. */
    @Transactional
    public List<IpRow> listAll(Long zoneId, Long subnetId, IpAddress.Status status,
                               String searchField, String keyword) {
        syncAll();

        List<IpAddress> all = ipRepository.findAll();
        List<IpAddress> filtered = new ArrayList<>();
        for (IpAddress ip : all) {
            if (zoneId != null && !zoneId.equals(ip.getZoneId())) continue;
            if (subnetId != null && !subnetId.equals(ip.getSubnetId())) continue;
            if (status != null && ip.getStatus() != status) continue;
            if (keyword != null && !keyword.isBlank() && !matches(ip, searchField, keyword.toLowerCase())) continue;
            filtered.add(ip);
        }
        return rows(filtered);
    }

    public IpRow getIp(Long ipId) {
        return rows(List.of(findIp(ipId))).get(0);
    }

    // ── 예약 생성 / 수정 / 삭제 ─────────────────────────────────

    /** IP 예약 생성 (단일 또는 범위). 자원에 즉시 할당되지 않는 예약 상태. */
    @Transactional
    public int reserve(Long subnetId, String ipStart, String ipEnd, String description,
                       String user, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        NetworkSubnet subnet = findSubnet(subnetId);
        List<String> targets = expand(subnet.getCidr(), ipStart, ipEnd);

        int created = 0;
        for (String ip : targets) {
            if (ipRepository.findBySubnetIdAndIpAddress(subnetId, ip).isPresent()) continue;
            ipRepository.save(new IpAddress(subnet.getZoneId(), subnetId, ip, description, user));
            created++;
        }
        audit.record(Target.IP, subnetId, Action.CREATE, Result.SUCCESS,
                "IP 주소 " + created + "건이 예약 되었습니다.", user, clientIp, startedAt);
        return created;
    }

    /** 설명 수정 (편집 모달은 설명만). */
    @Transactional
    public IpRow update(Long ipId, String description, String user, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        IpAddress ip = findIp(ipId);
        if (description != null) ip.setDescription(description);
        ip.setUpdatedBy(user);
        ipRepository.save(ip);
        audit.record(Target.IP, ip.getSubnetId(), Action.UPDATE, Result.SUCCESS,
                "IP 주소('" + ip.getIpAddress() + "')이/가 수정 되었습니다.", user, clientIp, startedAt);
        return rows(List.of(ip)).get(0);
    }

    @Transactional
    public void delete(Long ipId, String user, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        IpAddress ip = findIp(ipId);
        ipRepository.delete(ip);
        audit.record(Target.IP, ip.getSubnetId(), Action.DELETE, Result.SUCCESS,
                "IP 주소('" + ip.getIpAddress() + "')이/가 삭제 되었습니다.", user, clientIp, startedAt);
    }

    // ── OpenStack 동기화 ────────────────────────────────────────

    private void syncAll() {
        List<AllocatedIp> allocated = allocatedIps();
        for (NetworkSubnet subnet : subnetRepository.findAll()) {
            syncSubnet(subnet, allocated);
        }
    }

    /** 서브넷 CIDR에 속한 OpenStack 할당 IP를 반영 (별칭/설명 보존). */
    private void syncSubnet(NetworkSubnet subnet, List<AllocatedIp> allocated) {
        Map<String, IpAddress> existing = new HashMap<>();
        for (IpAddress ip : ipRepository.findBySubnetId(subnet.getId())) {
            existing.put(ip.getIpAddress(), ip);
        }

        Set<String> osIps = new HashSet<>();
        for (AllocatedIp a : allocated) {
            if (a.ip() == null || !CidrUtil.contains(subnet.getCidr(), a.ip())) continue;
            osIps.add(a.ip());
            IpAddress row = existing.get(a.ip());
            if (row == null) {
                row = new IpAddress(subnet.getZoneId(), subnet.getId(), a.ip(), null, "openstack-sync");
            }
            row.applyAllocation(a.resourceType(), a.resourceId(), a.resourceName(), a.macAddress(), a.portGroup());
            ipRepository.save(row);
        }

        // OpenStack에서 사라진 할당 IP: 자원 정보만 비우고 예약 상태로 (사용자 별칭/설명은 보존)
        for (IpAddress row : existing.values()) {
            if (row.getStatus() == IpAddress.Status.ALLOCATED && !osIps.contains(row.getIpAddress())) {
                if (row.getAlias() == null && row.getDescription() == null) {
                    ipRepository.delete(row); // 순수 동기화 IP였으면 제거
                } else {
                    row.clearAllocation();
                    ipRepository.save(row);
                }
            }
        }
    }

    private List<AllocatedIp> allocatedIps() {
        try {
            return networkCatalog.listAllocatedIps();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── 공통 ────────────────────────────────────────────────────

    private static boolean matches(IpAddress ip, String field, String keyword) {
        String f = field == null ? "IP" : field;
        return switch (f) {
            case "RESOURCE" -> contains(ip.getResourceName(), keyword);
            case "DESCRIPTION" -> contains(ip.getDescription(), keyword);
            default -> contains(ip.getIpAddress(), keyword);
        };
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    /** ipStart~ipEnd 범위를 IP 문자열 목록으로 (범위 없으면 단일). CIDR 밖은 제외. */
    private static List<String> expand(String cidr, String ipStart, String ipEnd) {
        if (ipStart == null || ipStart.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IP 주소를 입력해주세요");
        }
        if (!CidrUtil.contains(cidr, ipStart)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "서브넷 범위 밖의 IP입니다: " + ipStart);
        }
        if (ipEnd == null || ipEnd.isBlank() || ipEnd.equals(ipStart)) {
            return List.of(ipStart);
        }
        if (!CidrUtil.contains(cidr, ipEnd)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "서브넷 범위 밖의 IP입니다: " + ipEnd);
        }
        long start = CidrUtil.ipToLong(ipStart);
        long end = CidrUtil.ipToLong(ipEnd);
        if (end < start) { long t = start; start = end; end = t; }
        if (end - start > 1024) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "한 번에 예약 가능한 범위는 1024개까지입니다");
        }
        List<String> ips = new ArrayList<>();
        for (long v = start; v <= end; v++) {
            ips.add(((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + (v & 0xFF));
        }
        return ips;
    }

    private NetworkSubnet findSubnet(Long subnetId) {
        return subnetRepository.findById(subnetId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "서브넷을 찾을 수 없습니다: " + subnetId));
    }

    private IpAddress findIp(Long ipId) {
        return ipRepository.findById(ipId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "IP 주소를 찾을 수 없습니다: " + ipId));
    }

    private List<IpRow> rows(List<IpAddress> ips) {
        Map<Long, String> zoneNames = new HashMap<>();
        for (NetworkZone z : zoneRepository.findAll()) zoneNames.put(z.getId(), z.getName());
        Map<Long, String> cidrs = new HashMap<>();
        for (NetworkSubnet s : subnetRepository.findAll()) cidrs.put(s.getId(), s.getCidr());

        List<IpRow> result = new ArrayList<>(ips.size());
        for (IpAddress ip : ips) {
            result.add(new IpRow(ip.getId(), ip.getZoneId(), zoneNames.get(ip.getZoneId()),
                    ip.getSubnetId(), cidrs.get(ip.getSubnetId()), ip.getIpAddress(), ip.getStatus().name(),
                    ip.getResourceType(), ip.getResourceId(), ip.getResourceName(), ip.getAlias(),
                    ip.getPortGroup(), ip.getMacAddress(), ip.getDescription(),
                    ip.getCreatedBy(), ip.getUpdatedBy(), ip.getCreatedAt(), ip.getUpdatedAt()));
        }
        return result;
    }
}

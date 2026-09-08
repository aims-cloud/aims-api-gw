package com.innogrid.aims.infra.ipam.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.SubnetSummary;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Action;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Result;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Target;
import com.innogrid.aims.infra.ipam.entity.NetworkSubnet;
import com.innogrid.aims.infra.ipam.entity.NetworkZone;
import com.innogrid.aims.infra.ipam.repository.IpAddressRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkSubnetRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkZoneRepository;

/**
 * 서브넷 서비스 (IPAM Phase 2).
 * 서브넷 CRUD + IP 이용률(소속 IP 수 / CIDR 전체 호스트 수) + 행위이력.
 */
@Service
public class NetworkSubnetService {

    private final NetworkSubnetRepository subnetRepository;
    private final NetworkZoneRepository zoneRepository;
    private final IpAddressRepository ipRepository;
    private final NetworkAuditService audit;

    public NetworkSubnetService(NetworkSubnetRepository subnetRepository,
                                NetworkZoneRepository zoneRepository,
                                IpAddressRepository ipRepository,
                                NetworkAuditService audit) {
        this.subnetRepository = subnetRepository;
        this.zoneRepository = zoneRepository;
        this.ipRepository = ipRepository;
        this.audit = audit;
    }

    /** 전체 서브넷 (구역 필터 optional). */
    public List<SubnetSummary> listSubnets(Long zoneId) {
        List<NetworkSubnet> subnets = zoneId == null
                ? subnetRepository.findAll() : subnetRepository.findByZoneId(zoneId);
        Map<Long, String> zoneNames = zoneNames();
        List<SubnetSummary> result = new ArrayList<>(subnets.size());
        for (NetworkSubnet s : subnets) {
            result.add(toSummary(s, zoneNames.get(s.getZoneId())));
        }
        return result;
    }

    public SubnetSummary getSubnet(Long subnetId) {
        NetworkSubnet subnet = findSubnet(subnetId);
        return toSummary(subnet, zoneNames().get(subnet.getZoneId()));
    }

    @Transactional
    public SubnetSummary createSubnet(Long zoneId, String cidr, String gateway, String description,
                                      String user, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        if (zoneId == null || !zoneRepository.existsById(zoneId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "네트워크 구역을 선택해주세요");
        }
        if (cidr == null || !CidrUtil.isValidCidr(cidr)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "올바른 CIDR을 입력해주세요");
        }
        NetworkSubnet subnet = subnetRepository.save(new NetworkSubnet(zoneId, cidr, gateway, description, user));
        audit.record(Target.SUBNET, subnet.getId(), Action.CREATE, Result.SUCCESS,
                "서브넷('" + cidr + "')이/가 생성 되었습니다.", user, clientIp, startedAt);
        return toSummary(subnet, zoneNames().get(zoneId));
    }

    @Transactional
    public SubnetSummary updateSubnet(Long subnetId, String gateway, String description,
                                      String user, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        NetworkSubnet subnet = findSubnet(subnetId);
        if (gateway != null) subnet.setGateway(gateway);
        if (description != null) subnet.setDescription(description);
        subnet.setUpdatedBy(user);
        subnetRepository.save(subnet);
        audit.record(Target.SUBNET, subnetId, Action.UPDATE, Result.SUCCESS,
                "서브넷('" + subnet.getCidr() + "')이/가 수정 되었습니다.", user, clientIp, startedAt);
        return toSummary(subnet, zoneNames().get(subnet.getZoneId()));
    }

    @Transactional
    public void deleteSubnet(Long subnetId, String user, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        NetworkSubnet subnet = findSubnet(subnetId);
        ipRepository.deleteBySubnetId(subnetId);
        subnetRepository.delete(subnet);
        audit.record(Target.SUBNET, subnetId, Action.DELETE, Result.SUCCESS,
                "서브넷('" + subnet.getCidr() + "')이/가 삭제 되었습니다.", user, clientIp, startedAt);
    }

    // ── 공통 ────────────────────────────────────────────────────

    NetworkSubnet findSubnet(Long subnetId) {
        return subnetRepository.findById(subnetId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "서브넷을 찾을 수 없습니다: " + subnetId));
    }

    private SubnetSummary toSummary(NetworkSubnet subnet, String zoneName) {
        CidrUtil.Range range = CidrUtil.range(subnet.getCidr());
        long used = ipRepository.countBySubnetId(subnet.getId());
        double percent = range.totalHosts() == 0 ? 0.0
                : Math.round(used * 10000.0 / range.totalHosts()) / 100.0;
        return new SubnetSummary(subnet.getId(), subnet.getZoneId(), zoneName, subnet.getCidr(),
                range.first(), range.last(), range.totalHosts(), used, percent,
                subnet.getGateway(), subnet.getDescription(), subnet.getCreatedBy(), subnet.getUpdatedBy(),
                subnet.getCreatedAt(), subnet.getUpdatedAt());
    }

    private Map<Long, String> zoneNames() {
        Map<Long, String> names = new HashMap<>();
        for (NetworkZone z : zoneRepository.findAll()) {
            names.put(z.getId(), z.getName());
        }
        return names;
    }
}

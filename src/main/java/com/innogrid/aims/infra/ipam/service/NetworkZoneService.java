package com.innogrid.aims.infra.ipam.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.InterfaceCandidate;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.InterfaceRef;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.ZoneInterface;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.ZoneSummary;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Action;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Result;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Target;
import com.innogrid.aims.infra.ipam.entity.NetworkZone;
import com.innogrid.aims.infra.ipam.entity.NetworkZoneInterface;
import com.innogrid.aims.infra.ipam.repository.IpAddressRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkSubnetRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkZoneInterfaceRepository;
import com.innogrid.aims.infra.ipam.repository.NetworkZoneRepository;
import com.innogrid.aims.openstack.OpenStackNetworkCatalog;

/**
 * 네트워크 구역 서비스 (IPAM Phase 1).
 * 구역 CRUD + 인터페이스 관리(OpenStack 네트워크 연결) + 행위이력.
 */
@Service
public class NetworkZoneService {

    private final NetworkZoneRepository zoneRepository;
    private final NetworkZoneInterfaceRepository interfaceRepository;
    private final NetworkSubnetRepository subnetRepository;
    private final IpAddressRepository ipRepository;
    private final OpenStackNetworkCatalog networkCatalog;
    private final NetworkAuditService audit;

    public NetworkZoneService(NetworkZoneRepository zoneRepository,
                              NetworkZoneInterfaceRepository interfaceRepository,
                              NetworkSubnetRepository subnetRepository,
                              IpAddressRepository ipRepository,
                              OpenStackNetworkCatalog networkCatalog,
                              NetworkAuditService audit) {
        this.zoneRepository = zoneRepository;
        this.interfaceRepository = interfaceRepository;
        this.subnetRepository = subnetRepository;
        this.ipRepository = ipRepository;
        this.networkCatalog = networkCatalog;
        this.audit = audit;
    }

    public List<ZoneSummary> listZones() {
        List<ZoneSummary> result = new ArrayList<>();
        for (NetworkZone zone : zoneRepository.findAll()) {
            result.add(toSummary(zone));
        }
        return result;
    }

    public ZoneSummary getZone(Long zoneId) {
        return toSummary(findZone(zoneId));
    }

    @Transactional
    public ZoneSummary createZone(String name, String description, String user, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        if (name == null || name.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "이름을 입력해주세요");
        }
        if (zoneRepository.existsByName(name)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 존재하는 구역 이름입니다: " + name);
        }
        NetworkZone zone = zoneRepository.save(new NetworkZone(name, description, user));
        audit.record(Target.ZONE, zone.getId(), Action.CREATE, Result.SUCCESS,
                "네트워크 구역('" + name + "')이/가 생성 되었습니다.", user, clientIp, startedAt);
        return toSummary(zone);
    }

    @Transactional
    public ZoneSummary updateZone(Long zoneId, String name, String description, String user, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        NetworkZone zone = findZone(zoneId);
        if (name != null && !name.isBlank()) zone.setName(name);
        if (description != null) zone.setDescription(description);
        zone.setUpdatedBy(user);
        zoneRepository.save(zone);
        audit.record(Target.ZONE, zoneId, Action.UPDATE, Result.SUCCESS,
                "네트워크 구역('" + zone.getName() + "')이/가 수정 되었습니다.", user, clientIp, startedAt);
        return toSummary(zone);
    }

    @Transactional
    public void deleteZone(Long zoneId, String user, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        NetworkZone zone = findZone(zoneId);
        ipRepository.deleteByZoneId(zoneId);
        for (var subnet : subnetRepository.findByZoneId(zoneId)) {
            subnetRepository.delete(subnet);
        }
        interfaceRepository.deleteByZoneId(zoneId);
        zoneRepository.delete(zone);
        audit.record(Target.ZONE, zoneId, Action.DELETE, Result.SUCCESS,
                "네트워크 구역('" + zone.getName() + "')이/가 삭제 되었습니다.", user, clientIp, startedAt);
    }

    // ── 인터페이스 관리 ─────────────────────────────────────────

    public List<InterfaceCandidate> listInterfaceCandidates() {
        List<InterfaceCandidate> result = new ArrayList<>();
        for (OpenStackNetworkCatalog.NetworkOption n : networkCatalog.listNetworks()) {
            result.add(new InterfaceCandidate(n.deployZone(), n.networkName(), n.openstackNetworkId(), null));
        }
        return result;
    }

    public List<ZoneInterface> listInterfaces(Long zoneId) {
        findZone(zoneId);
        List<ZoneInterface> result = new ArrayList<>();
        for (NetworkZoneInterface i : interfaceRepository.findByZoneId(zoneId)) {
            result.add(new ZoneInterface(i.getId(), i.getDeployZone(), i.getNetworkName(),
                    i.getOpenstackNetworkId(), i.getDatacenter()));
        }
        return result;
    }

    /** 인터페이스 전체 교체 저장. */
    @Transactional
    public List<ZoneInterface> saveInterfaces(Long zoneId, List<InterfaceRef> refs, String user, String clientIp) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        NetworkZone zone = findZone(zoneId);
        interfaceRepository.deleteByZoneId(zoneId);
        if (refs != null) {
            for (InterfaceRef r : refs) {
                interfaceRepository.save(new NetworkZoneInterface(zoneId, r.deployZone(),
                        r.openstackNetworkId(), r.networkName(), r.datacenter()));
            }
        }
        audit.record(Target.INTERFACE, zoneId, Action.UPDATE, Result.SUCCESS,
                "네트워크 구역('" + zone.getName() + "')의 인터페이스가 수정 되었습니다.", user, clientIp, startedAt);
        return listInterfaces(zoneId);
    }

    // ── 공통 ────────────────────────────────────────────────────

    NetworkZone findZone(Long zoneId) {
        return zoneRepository.findById(zoneId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "네트워크 구역을 찾을 수 없습니다: " + zoneId));
    }

    private ZoneSummary toSummary(NetworkZone zone) {
        return new ZoneSummary(zone.getId(), zone.getName(), zone.getDescription(),
                zone.getCreatedBy(), zone.getUpdatedBy(), zone.getCreatedAt(), zone.getUpdatedAt(),
                (int) subnetRepository.countByZoneId(zone.getId()),
                interfaceRepository.findByZoneId(zone.getId()).size());
    }
}

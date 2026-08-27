package com.innogrid.aims.openstack.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.openstack.NeutronClient;

import static com.innogrid.aims.openstack.service.NeutronMappers.extractList;
import static com.innogrid.aims.openstack.service.NeutronMappers.extractMap;
import static com.innogrid.aims.openstack.service.NeutronMappers.toInterfaceSummary;
import static com.innogrid.aims.openstack.service.NeutronMappers.toSubnetSummary;
import static com.innogrid.aims.openstack.service.NeutronMappers.toZoneSummary;

/**
 * 네트워크 구역 서비스.
 * OpenStack Neutron 네트워크를 화면의 '네트워크 구역' 모델로 매핑하여 표출한다.
 * 1~3세부가 모두 OpenStack을 사용하므로 Neutron이 단일 원본이다 (조회 전용).
 */
@Service
public class NetworkZoneService {

    private final NeutronClient neutron;

    public NetworkZoneService(NeutronClient neutron) {
        this.neutron = neutron;
    }

    /** 구역 목록 — Neutron 네트워크 목록을 구역 요약으로 변환. */
    public List<Map<String, Object>> listZones() {
        List<Map<String, Object>> networks = extractList(neutron.listNetworks(), "networks");
        List<Map<String, Object>> zones = new ArrayList<>(networks.size());
        for (Map<String, Object> network : networks) {
            zones.add(toZoneSummary(network));
        }
        return zones;
    }

    /** 구역 상세 — 단일 Neutron 네트워크. */
    public Map<String, Object> getZone(String networkId) {
        Map<String, Object> response = neutron.getNetwork(networkId);
        Map<String, Object> network = extractMap(response, "network");
        if (network.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "네트워크 구역을 찾을 수 없습니다: " + networkId);
        }
        return toZoneSummary(network);
    }

    /** 구역 상세 — 서브넷 탭. */
    public List<Map<String, Object>> getZoneSubnets(String networkId) {
        List<Map<String, Object>> subnets =
                extractList(neutron.listSubnetsByNetwork(networkId), "subnets");
        List<Map<String, Object>> result = new ArrayList<>(subnets.size());
        for (Map<String, Object> subnet : subnets) {
            result.add(toSubnetSummary(subnet));
        }
        return result;
    }

    /** 구역 상세 — 네트워크 인터페이스(포트) 탭. */
    public List<Map<String, Object>> getZoneInterfaces(String networkId) {
        List<Map<String, Object>> ports =
                extractList(neutron.listPortsByNetwork(networkId), "ports");
        List<Map<String, Object>> result = new ArrayList<>(ports.size());
        for (Map<String, Object> port : ports) {
            result.add(toInterfaceSummary(port));
        }
        return result;
    }
}

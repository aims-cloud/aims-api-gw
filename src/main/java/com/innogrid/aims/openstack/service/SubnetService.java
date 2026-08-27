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
import static com.innogrid.aims.openstack.service.NeutronMappers.toIpRows;
import static com.innogrid.aims.openstack.service.NeutronMappers.toSubnetSummary;

/**
 * 서브넷 / IP주소 서비스.
 * OpenStack Neutron 서브넷·포트를 화면의 '서브넷', 'IP주소' 모델로 매핑하여 표출한다 (조회 전용).
 * IP주소는 포트의 fixed_ips를 IP 단위 행으로 펼쳐서 제공한다.
 */
@Service
public class SubnetService {

    private final NeutronClient neutron;

    public SubnetService(NeutronClient neutron) {
        this.neutron = neutron;
    }

    // ── 서브넷 ──────────────────────────────────────────────────

    /** 서브넷 목록. networkId(네트워크 구역)로 필터 가능. */
    public List<Map<String, Object>> listSubnets(String networkId) {
        Map<String, Object> response = networkId == null || networkId.isBlank()
                ? neutron.listSubnets()
                : neutron.listSubnetsByNetwork(networkId);
        List<Map<String, Object>> subnets = extractList(response, "subnets");
        List<Map<String, Object>> result = new ArrayList<>(subnets.size());
        for (Map<String, Object> subnet : subnets) {
            result.add(toSubnetSummary(subnet));
        }
        return result;
    }

    /** 서브넷 상세. */
    public Map<String, Object> getSubnet(String subnetId) {
        Map<String, Object> subnet = extractMap(neutron.getSubnet(subnetId), "subnet");
        if (subnet.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "서브넷을 찾을 수 없습니다: " + subnetId);
        }
        return toSubnetSummary(subnet);
    }

    /** 서브넷 상세 — IP주소 탭. 해당 서브넷에 할당된 IP만 반환한다. */
    public List<Map<String, Object>> getSubnetIps(String subnetId) {
        List<Map<String, Object>> ports =
                extractList(neutron.listPortsBySubnet(subnetId), "ports");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> port : ports) {
            rows.addAll(toIpRows(port, subnetId));
        }
        return rows;
    }

    // ── IP주소 ──────────────────────────────────────────────────

    /** IP주소 목록. 네트워크구역(networkId) / 서브넷(subnetId) 필터 지원. */
    public List<Map<String, Object>> listIps(String networkId, String subnetId) {
        Map<String, Object> response;
        if (subnetId != null && !subnetId.isBlank()) {
            response = neutron.listPortsBySubnet(subnetId);
        } else if (networkId != null && !networkId.isBlank()) {
            response = neutron.listPortsByNetwork(networkId);
        } else {
            response = neutron.listPorts();
        }

        String subnetFilter = subnetId == null || subnetId.isBlank() ? null : subnetId;
        List<Map<String, Object>> ports = extractList(response, "ports");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> port : ports) {
            rows.addAll(toIpRows(port, subnetFilter));
        }
        return rows;
    }

    /** IP주소 상세 — 포트 단위 상세 조회. */
    public Map<String, Object> getIpDetail(String portId) {
        Map<String, Object> port = extractMap(neutron.getPort(portId), "port");
        if (port.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "IP(포트)를 찾을 수 없습니다: " + portId);
        }
        List<Map<String, Object>> rows = toIpRows(port, null);
        Map<String, Object> detail = NeutronMappers.toInterfaceSummary(port);
        detail.put("ips", rows);
        return detail;
    }
}

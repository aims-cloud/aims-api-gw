package com.innogrid.aims.openstack;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.innogrid.aims.common.exception.BusinessException;

/**
 * OpenStack Neutron(네트워크) API 클라이언트.
 * 네트워크/서브넷/포트 조회를 제공하며, 인증 토큰은 {@link KeystoneClient}에서 받아 재사용한다.
 *
 * 네트워크 메뉴는 1~3세부 공통 OpenStack 기준으로 표출하므로 조회(read) 위주로 구성한다.
 * 자원 생성/변경은 각 세부과제 소유 OpenStack에서 직접 수행한다.
 */
@Component
public class NeutronClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final OpenStackProperties props;
    private final KeystoneClient keystone;
    private final RestClient rest;

    @Autowired
    public NeutronClient(OpenStackProperties props, KeystoneClient keystone) {
        this(props, keystone, RestClient.builder()
                .baseUrl(props.getNetworkUrl() == null ? "" : props.getNetworkUrl())
                .defaultHeader("Content-Type", "application/json")
                .build());
    }

    NeutronClient(OpenStackProperties props, KeystoneClient keystone, RestClient rest) {
        this.props = props;
        this.keystone = keystone;
        this.rest = rest;
    }

    // ── 네트워크 (Figma '네트워크 구역'의 원본 데이터) ──────────

    public Map<String, Object> listNetworks() {
        return get("/v2.0/networks");
    }

    public Map<String, Object> getNetwork(String networkId) {
        return get("/v2.0/networks/{id}", networkId);
    }

    // ── 서브넷 ──────────────────────────────────────────────────

    public Map<String, Object> listSubnets() {
        return get("/v2.0/subnets");
    }

    public Map<String, Object> listSubnetsByNetwork(String networkId) {
        return get("/v2.0/subnets?network_id={id}", networkId);
    }

    public Map<String, Object> getSubnet(String subnetId) {
        return get("/v2.0/subnets/{id}", subnetId);
    }

    // ── 라우터 (토폴로지 네트워크 레이어) ───────────────────────

    public Map<String, Object> listRouters() {
        return get("/v2.0/routers");
    }

    // ── 포트 (네트워크 인터페이스 / IP 할당 현황) ───────────────

    public Map<String, Object> listPorts() {
        return get("/v2.0/ports");
    }

    public Map<String, Object> listPortsByNetwork(String networkId) {
        return get("/v2.0/ports?network_id={id}", networkId);
    }

    public Map<String, Object> listPortsBySubnet(String subnetId) {
        // Neutron 규격: ?fixed_ips=subnet_id=<id> (query 값 내 '=' 는 인코딩 불필요)
        return get("/v2.0/ports?fixed_ips=subnet_id={id}", subnetId);
    }

    public Map<String, Object> getPort(String portId) {
        return get("/v2.0/ports/{id}", portId);
    }

    // ── 공통 ────────────────────────────────────────────────────

    private Map<String, Object> get(String uri, Object... uriVariables) {
        requireConfigured();
        return rest.get()
                .uri(uri, uriVariables)
                .header("X-Auth-Token", keystone.getToken())
                .retrieve()
                .body(MAP_TYPE);
    }

    private void requireConfigured() {
        if (props.getNetworkUrl() == null || props.getNetworkUrl().isBlank()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenStack Neutron 연동이 설정되지 않았습니다 (OPENSTACK_NETWORK_URL 환경변수 필요)");
        }
    }
}

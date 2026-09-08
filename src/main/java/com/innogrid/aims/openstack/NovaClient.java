package com.innogrid.aims.openstack;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.innogrid.aims.common.exception.BusinessException;

/**
 * OpenStack Nova(컴퓨트) API 클라이언트.
 * 토폴로지의 VM 계층(이름/상태)을 채우기 위한 조회 전용 클라이언트.
 * compute-url은 버전을 포함한 엔드포인트여야 한다 (예: http://host:8774/v2.1).
 */
@Component
public class NovaClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final OpenStackProperties props;
    private final KeystoneClient keystone;
    private final RestClient rest;

    @Autowired
    public NovaClient(OpenStackProperties props, KeystoneClient keystone) {
        this(props, keystone, OpenStackRestClients.build(props.getComputeUrl(), props.isVerifyTls()));
    }

    NovaClient(OpenStackProperties props, KeystoneClient keystone, RestClient rest) {
        this.props = props;
        this.keystone = keystone;
        this.rest = rest;
    }

    /** VM(서버) 목록 — 상세 필드 포함. */
    public Map<String, Object> listServers() {
        requireConfigured();
        return rest.get()
                .uri("/servers/detail")
                .header("X-Auth-Token", keystone.getToken())
                .retrieve()
                .body(MAP_TYPE);
    }

    /** VM 단건 조회. */
    public Map<String, Object> getServer(String serverId) {
        requireConfigured();
        return rest.get()
                .uri("/servers/{id}", serverId)
                .header("X-Auth-Token", keystone.getToken())
                .retrieve()
                .body(MAP_TYPE);
    }

    /** 모든 프로젝트의 VM 목록 (관리자 토큰 필요). 토폴로지 컴퓨트 레이어용. */
    public Map<String, Object> listServersAllTenants() {
        requireConfigured();
        return rest.get()
                .uri("/servers/detail?all_tenants=1")
                .header("X-Auth-Token", keystone.getToken())
                .retrieve()
                .body(MAP_TYPE);
    }

    /** 가용영역 목록 — 토폴로지 컴퓨트 레이어 최상위. */
    public Map<String, Object> listAvailabilityZones() {
        requireConfigured();
        return rest.get()
                .uri("/os-availability-zone")
                .header("X-Auth-Token", keystone.getToken())
                .retrieve()
                .body(MAP_TYPE);
    }

    /** 하이퍼바이저 목록 (관리자 토큰 필요) — 물리노드/하이퍼바이저 상태. */
    public Map<String, Object> listHypervisors() {
        requireConfigured();
        return rest.get()
                .uri("/os-hypervisors/detail")
                .header("X-Auth-Token", keystone.getToken())
                .retrieve()
                .body(MAP_TYPE);
    }

    private void requireConfigured() {
        if (props.getComputeUrl() == null || props.getComputeUrl().isBlank()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenStack Nova 연동이 설정되지 않았습니다 (OPENSTACK_COMPUTE_URL 환경변수 필요)");
        }
    }
}

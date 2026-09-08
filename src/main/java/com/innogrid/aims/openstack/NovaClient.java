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
        this(props, keystone, RestClient.builder()
                .baseUrl(props.getComputeUrl() == null ? "" : props.getComputeUrl())
                .defaultHeader("Content-Type", "application/json")
                .build());
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

    private void requireConfigured() {
        if (props.getComputeUrl() == null || props.getComputeUrl().isBlank()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenStack Nova 연동이 설정되지 않았습니다 (OPENSTACK_COMPUTE_URL 환경변수 필요)");
        }
    }
}

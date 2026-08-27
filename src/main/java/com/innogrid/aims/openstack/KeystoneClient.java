package com.innogrid.aims.openstack;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.innogrid.aims.common.exception.BusinessException;

/**
 * OpenStack Keystone v3 인증 클라이언트.
 * password 인증으로 프로젝트 스코프 토큰을 발급받고, 만료 전까지 캐시하여 재사용한다.
 */
@Component
public class KeystoneClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    /** 만료 시각보다 이만큼 먼저 토큰을 갱신한다. */
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final OpenStackProperties props;
    private final RestClient rest;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public KeystoneClient(OpenStackProperties props) {
        this(props, RestClient.builder()
                .baseUrl(props.getAuthUrl() == null ? "" : props.getAuthUrl())
                .defaultHeader("Content-Type", "application/json")
                .build());
    }

    KeystoneClient(OpenStackProperties props, RestClient rest) {
        this.props = props;
        this.rest = rest;
    }

    /**
     * 유효한 토큰을 반환한다. 캐시된 토큰이 만료 임박이면 새로 발급받는다.
     */
    public String getToken() {
        requireConfigured();
        if (isTokenValid()) {
            return cachedToken;
        }
        synchronized (this) {
            if (isTokenValid()) {
                return cachedToken;
            }
            issueToken();
            return cachedToken;
        }
    }

    /** 캐시를 비운다. 401 발생 시 재발급을 유도하기 위해 사용한다. */
    public synchronized void invalidate() {
        cachedToken = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    private boolean isTokenValid() {
        return cachedToken != null
                && Instant.now().isBefore(tokenExpiresAt.minus(REFRESH_MARGIN));
    }

    @SuppressWarnings("unchecked")
    private void issueToken() {
        String domain = props.getDomainName() == null || props.getDomainName().isBlank()
                ? "Default" : props.getDomainName();

        Map<String, Object> body = Map.of("auth", Map.of(
                "identity", Map.of(
                        "methods", List.of("password"),
                        "password", Map.of("user", Map.of(
                                "name", props.getUsername(),
                                "domain", Map.of("name", domain),
                                "password", props.getPassword()))),
                "scope", Map.of("project", Map.of(
                        "name", props.getProjectName(),
                        "domain", Map.of("name", domain)))));

        ResponseEntity<Map<String, Object>> response = rest.post()
                .uri("/auth/tokens")
                .body(body)
                .retrieve()
                .toEntity(MAP_TYPE);

        String token = response.getHeaders().getFirst("X-Subject-Token");
        if (token == null || token.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY,
                    "Keystone 응답에 X-Subject-Token 헤더가 없습니다");
        }

        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(30));
        Map<String, Object> responseBody = response.getBody();
        if (responseBody != null && responseBody.get("token") instanceof Map<?, ?> tokenInfo) {
            Object expires = ((Map<String, Object>) tokenInfo).get("expires_at");
            if (expires != null) {
                expiresAt = Instant.parse(expires.toString());
            }
        }

        this.cachedToken = token;
        this.tokenExpiresAt = expiresAt;
    }

    private void requireConfigured() {
        if (!props.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenStack 연동이 설정되지 않았습니다 (OPENSTACK_AUTH_URL 등 환경변수 필요)");
        }
    }
}

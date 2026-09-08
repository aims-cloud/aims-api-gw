package com.innogrid.aims.openstack;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenStack Glance(이미지) API 클라이언트.
 * VM 상세의 이미지 이름 표기에 사용한다. image-url 미설정 시 조회는 호출 측에서 생략한다.
 */
@Component
public class GlanceClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final OpenStackProperties props;
    private final KeystoneClient keystone;
    private final RestClient rest;

    @Autowired
    public GlanceClient(OpenStackProperties props, KeystoneClient keystone) {
        this(props, keystone, OpenStackRestClients.build(props.getImageUrl(), props.isVerifyTls()));
    }

    GlanceClient(OpenStackProperties props, KeystoneClient keystone, RestClient rest) {
        this.props = props;
        this.keystone = keystone;
        this.rest = rest;
    }

    public boolean isConfigured() {
        return props.getImageUrl() != null && !props.getImageUrl().isBlank();
    }

    /** 이미지 단건 조회 (Glance v2). 응답 자체가 이미지 객체다. */
    public Map<String, Object> getImage(String imageId) {
        return rest.get()
                .uri("/v2/images/{id}", imageId)
                .header("X-Auth-Token", keystone.getToken())
                .retrieve()
                .body(MAP_TYPE);
    }
}

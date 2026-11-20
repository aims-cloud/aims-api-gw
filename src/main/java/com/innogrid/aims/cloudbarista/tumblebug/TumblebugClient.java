package com.innogrid.aims.cloudbarista.tumblebug;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * CB-Tumblebug REST API 클라이언트.
 * CB-Spider 위에서 동작하는 멀티클라우드 인프라 오케스트레이션 프레임워크.
 */
@Component
public class TumblebugClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient rest;

    public TumblebugClient(@Qualifier("tumblebugRestClient") RestClient rest) {
        this.rest = rest;
    }

    // ── 네임스페이스 (NS-01, NS-02) ─────────────────────────────

    public Map<String, Object> createNamespace(Map<String, Object> body) {
        return rest.post().uri("/ns").body(body).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listNamespaces() {
        return rest.get().uri("/ns").retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> getNamespace(String nsId) {
        return rest.get().uri("/ns/{ns}", nsId).retrieve().body(MAP_TYPE);
    }

    public void deleteNamespace(String nsId) {
        rest.delete().uri("/ns/{ns}", nsId).retrieve().toBodilessEntity();
    }

    // ── 인프라(MCIS) 프로비저닝 (VM-07 ~ VM-10) ─────────────────

    public Map<String, Object> createInfra(String nsId, Map<String, Object> body) {
        return rest.post().uri("/ns/{ns}/infra", nsId).body(body).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listInfra(String nsId) {
        return rest.get().uri("/ns/{ns}/infra", nsId).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> getInfra(String nsId, String infraId) {
        return rest.get().uri("/ns/{ns}/infra/{id}", nsId, infraId).retrieve().body(MAP_TYPE);
    }

    public void deleteInfra(String nsId, String infraId) {
        rest.delete().uri("/ns/{ns}/infra/{id}", nsId, infraId).retrieve().toBodilessEntity();
    }

    /** Node(VM) 상세 — spec·image·IP·connection·cspResourceId 등 (자원 탐색기 VM 정보 표) */
    public Map<String, Object> getNode(String nsId, String infraId, String nodeId) {
        return rest.get().uri("/ns/{ns}/infra/{id}/node/{nodeId}", nsId, infraId, nodeId).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> sendCommand(String nsId, String infraId, Map<String, Object> body) {
        return rest.post().uri("/ns/{ns}/cmd/infra/{id}", nsId, infraId)
                .body(body).retrieve().body(MAP_TYPE);
    }

    /**
     * Infra 안 Node(VM) 한 대에만 원격 명령을 실행한다 — {@code POST /ns/{ns}/cmd/infra/{infra}?nodeId=}.
     * 동기 호출이라 명령이 끝날 때까지(본문 timeoutMinutes 까지) 응답을 기다린다. 응답은 model.InfraSshCmdResultForAPI
     * ({@code results[].nodeId·stdout·stderr·error}).
     */
    public Map<String, Object> sendNodeCommand(String nsId, String infraId, String nodeId, Map<String, Object> body) {
        return rest.post().uri(b -> b.path("/ns/{ns}/cmd/infra/{id}").queryParam("nodeId", "{nodeId}")
                        .build(nsId, infraId, nodeId))
                .body(body).retrieve().body(MAP_TYPE);
    }

    // ── 공통 리소스 (이미지, 스펙, VPC, 보안그룹, SSH키) ──────────

    public Map<String, Object> listImages(String nsId) {
        return rest.get().uri("/ns/{ns}/resources/image", nsId).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listSpecs(String nsId) {
        return rest.get().uri("/ns/{ns}/resources/spec", nsId).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listVNets(String nsId) {
        return rest.get().uri("/ns/{ns}/resources/vNet", nsId).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listSecurityGroups(String nsId) {
        return rest.get().uri("/ns/{ns}/resources/securityGroup", nsId).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listSshKeys(String nsId) {
        return rest.get().uri("/ns/{ns}/resources/sshKey", nsId).retrieve().body(MAP_TYPE);
    }

    // ── 레이블/태깅 (TAG-01 ~ TAG-03) ───────────────────────────

    public Map<String, Object> getLabel(String labelType, String uid) {
        return rest.get().uri("/label/{type}/{uid}", labelType, uid).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> setLabel(String labelType, String uid, Map<String, Object> body) {
        return rest.put().uri("/label/{type}/{uid}", labelType, uid)
                .body(body).retrieve().body(MAP_TYPE);
    }

    public void deleteLabel(String labelType, String uid, String key) {
        rest.delete().uri("/label/{type}/{uid}/{key}", labelType, uid, key)
                .retrieve().toBodilessEntity();
    }

    // ── 시스템 ──────────────────────────────────────────────────

    public Map<String, Object> checkLiveness() {
        return rest.get().uri("/livez").retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> getConnConfig(String connConfigName) {
        return rest.get().uri("/connConfig/{name}", connConfigName).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listConnConfigs() {
        return rest.get().uri("/connConfig").retrieve().body(MAP_TYPE);
    }
}

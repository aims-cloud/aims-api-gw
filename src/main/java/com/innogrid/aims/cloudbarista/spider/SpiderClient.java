package com.innogrid.aims.cloudbarista.spider;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import static com.innogrid.aims.cloudbarista.spider.SpiderRequestConverter.toSpider;

/**
 * CB-Spider REST API 클라이언트.
 * 멀티 클라우드(AWS, GCP, Azure, NCP 등)에 대한 단일 통합 API를 제공한다.
 *
 * 쓰기 요청(POST/PUT)은 {@link SpiderRequestConverter}를 통해
 * camelCase → CB-Spider PascalCase 필드명 변환을 수행한다.
 * DELETE는 ConnectionName을 요청 본문으로 전달한다 (CB-Spider 규격).
 */
@Component
public class SpiderClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient rest;

    public SpiderClient(@Qualifier("spiderRestClient") RestClient rest) {
        this.rest = rest;
    }

    // ── 클라우드 연결 관리 (CON-01 ~ CON-05) ───────────────────

    public Map<String, Object> createConnectionConfig(Map<String, Object> body) {
        return rest.post().uri("/connectionconfig").body(body).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listConnectionConfigs() {
        return rest.get().uri("/connectionconfig").retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> getConnectionConfig(String configName) {
        return rest.get().uri("/connectionconfig/{name}", configName).retrieve().body(MAP_TYPE);
    }

    public void deleteConnectionConfig(String configName) {
        rest.delete().uri("/connectionconfig/{name}", configName).retrieve().toBodilessEntity();
    }

    public Map<String, Object> createCredential(Map<String, Object> body) {
        return rest.post().uri("/credential").body(body).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listCredentials() {
        return rest.get().uri("/credential").retrieve().body(MAP_TYPE);
    }

    public void deleteCredential(String credentialName) {
        rest.delete().uri("/credential/{name}", credentialName).retrieve().toBodilessEntity();
    }

    public Map<String, Object> registerDriver(Map<String, Object> body) {
        return rest.post().uri("/driver").body(body).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listDrivers() {
        return rest.get().uri("/driver").retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> createRegion(Map<String, Object> body) {
        return rest.post().uri("/region").body(body).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listRegions() {
        return rest.get().uri("/region").retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> healthCheck() {
        return rest.get().uri("/healthcheck").retrieve().body(MAP_TYPE);
    }

    // ── VPC / 서브넷 (NET-01 ~ NET-02) ─────────────────────────

    public Map<String, Object> createVpc(Map<String, Object> body) {
        return rest.post().uri("/vpc").body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listVpcs(String connectionName) {
        return rest.get().uri("/vpc?ConnectionName={c}", connectionName).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> getVpc(String connectionName, String vpcName) {
        return rest.get().uri("/vpc/{name}?ConnectionName={c}", vpcName, connectionName)
                .retrieve().body(MAP_TYPE);
    }

    public void deleteVpc(String connectionName, String vpcName) {
        rest.method(HttpMethod.DELETE).uri("/vpc/{name}", vpcName)
                .body(Map.of("ConnectionName", connectionName))
                .retrieve().toBodilessEntity();
    }

    public Map<String, Object> addSubnet(String vpcName, Map<String, Object> body) {
        return rest.post().uri("/vpc/{vpc}/subnet", vpcName)
                .body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public void deleteSubnet(String connectionName, String vpcName, String subnetName) {
        rest.method(HttpMethod.DELETE).uri("/vpc/{vpc}/subnet/{sub}", vpcName, subnetName)
                .body(Map.of("ConnectionName", connectionName))
                .retrieve().toBodilessEntity();
    }

    // ── 보안그룹 (NET-03 ~ NET-04) ──────────────────────────────

    public Map<String, Object> createSecurityGroup(Map<String, Object> body) {
        return rest.post().uri("/securitygroup").body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listSecurityGroups(String connectionName) {
        return rest.get().uri("/securitygroup?ConnectionName={c}", connectionName)
                .retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> getSecurityGroup(String connectionName, String sgName) {
        return rest.get().uri("/securitygroup/{name}?ConnectionName={c}", sgName, connectionName)
                .retrieve().body(MAP_TYPE);
    }

    public void deleteSecurityGroup(String connectionName, String sgName) {
        rest.method(HttpMethod.DELETE).uri("/securitygroup/{name}", sgName)
                .body(Map.of("ConnectionName", connectionName))
                .retrieve().toBodilessEntity();
    }

    public Map<String, Object> addSecurityRules(String sgName, Map<String, Object> body) {
        return rest.post().uri("/securitygroup/{sg}/rules", sgName)
                .body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public void deleteSecurityRules(String sgName, Map<String, Object> body) {
        rest.method(HttpMethod.DELETE)
                .uri("/securitygroup/{sg}/rules", sgName)
                .body(toSpider(body)).retrieve().toBodilessEntity();
    }

    // ── 키페어 (NET-05) ─────────────────────────────────────────

    public Map<String, Object> createKeyPair(Map<String, Object> body) {
        return rest.post().uri("/keypair").body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listKeyPairs(String connectionName) {
        return rest.get().uri("/keypair?ConnectionName={c}", connectionName).retrieve().body(MAP_TYPE);
    }

    public void deleteKeyPair(String connectionName, String keyPairName) {
        rest.method(HttpMethod.DELETE).uri("/keypair/{name}", keyPairName)
                .body(Map.of("ConnectionName", connectionName))
                .retrieve().toBodilessEntity();
    }

    // ── VM (VM-01 ~ VM-06) ──────────────────────────────────────

    public Map<String, Object> createVm(Map<String, Object> body) {
        return rest.post().uri("/vm").body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listVMs(String connectionName) {
        return rest.get().uri("/vm?ConnectionName={c}", connectionName).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> getVM(String connectionName, String vmName) {
        return rest.get().uri("/vm/{name}?ConnectionName={c}", vmName, connectionName)
                .retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> getVMStatus(String connectionName, String vmName) {
        return rest.get().uri("/vmstatus/{name}?ConnectionName={c}", vmName, connectionName)
                .retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> controlVM(String connectionName, String vmName, String action) {
        return rest.get().uri("/controlvm/{name}?ConnectionName={c}&action={a}", vmName, connectionName, action)
                .retrieve().body(MAP_TYPE);
    }

    public void deleteVM(String connectionName, String vmName) {
        rest.method(HttpMethod.DELETE).uri("/vm/{name}", vmName)
                .body(Map.of("ConnectionName", connectionName))
                .retrieve().toBodilessEntity();
    }

    // ── 디스크 (DSK-01 ~ DSK-05) ────────────────────────────────

    public Map<String, Object> createDisk(Map<String, Object> body) {
        return rest.post().uri("/disk").body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listDisks(String connectionName) {
        return rest.get().uri("/disk?ConnectionName={c}", connectionName).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> getDisk(String connectionName, String diskName) {
        return rest.get().uri("/disk/{name}?ConnectionName={c}", diskName, connectionName)
                .retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> resizeDisk(String diskName, Map<String, Object> body) {
        return rest.put().uri("/disk/{name}/size", diskName)
                .body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> attachDisk(String diskName, Map<String, Object> body) {
        return rest.put().uri("/disk/{name}/attach", diskName)
                .body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> detachDisk(String diskName, Map<String, Object> body) {
        return rest.put().uri("/disk/{name}/detach", diskName)
                .body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public void deleteDisk(String connectionName, String diskName) {
        rest.method(HttpMethod.DELETE).uri("/disk/{name}", diskName)
                .body(Map.of("ConnectionName", connectionName))
                .retrieve().toBodilessEntity();
    }

    // ── NLB (NLB-01 ~ NLB-03) ───────────────────────────────────

    public Map<String, Object> createNlb(Map<String, Object> body) {
        return rest.post().uri("/nlb").body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listNlbs(String connectionName) {
        return rest.get().uri("/nlb?ConnectionName={c}", connectionName).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> getNlb(String connectionName, String nlbName) {
        return rest.get().uri("/nlb/{name}?ConnectionName={c}", nlbName, connectionName)
                .retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> addNlbVMs(String nlbName, Map<String, Object> body) {
        return rest.post().uri("/nlb/{name}/vms", nlbName)
                .body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public void removeNlbVMs(String nlbName, Map<String, Object> body) {
        rest.method(HttpMethod.DELETE)
                .uri("/nlb/{name}/vms", nlbName)
                .body(toSpider(body)).retrieve().toBodilessEntity();
    }

    public void deleteNlb(String connectionName, String nlbName) {
        rest.method(HttpMethod.DELETE).uri("/nlb/{name}", nlbName)
                .body(Map.of("ConnectionName", connectionName))
                .retrieve().toBodilessEntity();
    }

    // ── 이미지 / VM 스펙 (VM-11, VM-12) ─────────────────────────

    public Map<String, Object> listImages(String connectionName) {
        return rest.get().uri("/vmimage?ConnectionName={c}", connectionName).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> getImage(String connectionName, String imageName) {
        return rest.get().uri("/vmimage/{name}?ConnectionName={c}", imageName, connectionName)
                .retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listVMSpecs(String connectionName) {
        return rest.get().uri("/vmspec?ConnectionName={c}", connectionName).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> getVMSpec(String connectionName, String specName) {
        return rest.get().uri("/vmspec/{name}?ConnectionName={c}", specName, connectionName)
                .retrieve().body(MAP_TYPE);
    }

    // ── 스냅샷/MyImage (VM-13, VM-14) ───────────────────────────

    public Map<String, Object> createMyImage(Map<String, Object> body) {
        return rest.post().uri("/myimage").body(toSpider(body)).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> listMyImages(String connectionName) {
        return rest.get().uri("/myimage?ConnectionName={c}", connectionName).retrieve().body(MAP_TYPE);
    }

    public void deleteMyImage(String connectionName, String imageName) {
        rest.method(HttpMethod.DELETE).uri("/myimage/{name}", imageName)
                .body(Map.of("ConnectionName", connectionName))
                .retrieve().toBodilessEntity();
    }

    // ── 모니터링 (MON-01) ───────────────────────────────────────

    public Map<String, Object> getVMMonitoring(String connectionName, String vmName, String metricType) {
        return rest.get()
                .uri("/monitoring/vm/{vm}/{metric}?ConnectionName={c}", vmName, metricType, connectionName)
                .retrieve().body(MAP_TYPE);
    }
}

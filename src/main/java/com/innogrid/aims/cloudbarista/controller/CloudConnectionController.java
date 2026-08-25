package com.innogrid.aims.cloudbarista.controller;

import java.util.Map;

import com.innogrid.aims.cloudbarista.spider.SpiderClient;
import com.innogrid.aims.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "클라우드 연결 관리", description = "CSP 드라이버·자격증명·리전·연결설정 등록/조회/삭제 (CB-Spider)")
@RestController
@RequestMapping("/api/v1/cloud/connections")
public class CloudConnectionController {

    private final SpiderClient spiderClient;

    public CloudConnectionController(SpiderClient spiderClient) {
        this.spiderClient = spiderClient;
    }

    // ── 드라이버 ────────────────────────────────────────────────

    @Operation(summary = "드라이버 등록",
            description = "CSP 드라이버를 등록한다. DriverName, ProviderName, DriverLibFileName 필수")
    @PostMapping("/drivers")
    public ApiResponse<?> registerDriver(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(spiderClient.registerDriver(body));
    }

    @Operation(summary = "드라이버 목록 조회")
    @GetMapping("/drivers")
    public ApiResponse<?> listDrivers() {
        return ApiResponse.ok(spiderClient.listDrivers());
    }

    // ── 자격증명 ────────────────────────────────────────────────

    @Operation(summary = "자격증명 등록",
            description = "CSP 접속용 자격증명을 등록한다. CredentialName, ProviderName, KeyValueInfoList 필수")
    @PostMapping("/credentials")
    public ApiResponse<?> createCredential(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(spiderClient.createCredential(body));
    }

    @Operation(summary = "자격증명 목록 조회")
    @GetMapping("/credentials")
    public ApiResponse<?> listCredentials() {
        return ApiResponse.ok(spiderClient.listCredentials());
    }

    @Operation(summary = "자격증명 삭제")
    @DeleteMapping("/credentials/{credentialName}")
    public ApiResponse<Void> deleteCredential(@PathVariable String credentialName) {
        spiderClient.deleteCredential(credentialName);
        return ApiResponse.ok(null);
    }

    // ── 리전 ────────────────────────────────────────────────────

    @Operation(summary = "리전 등록",
            description = "CSP 리전/존 정보를 등록한다. RegionName, ProviderName, KeyValueInfoList 필수")
    @PostMapping("/regions")
    public ApiResponse<?> createRegion(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(spiderClient.createRegion(body));
    }

    @Operation(summary = "리전 목록 조회")
    @GetMapping("/regions")
    public ApiResponse<?> listRegions() {
        return ApiResponse.ok(spiderClient.listRegions());
    }

    // ── 연결 설정 ───────────────────────────────────────────────

    @Operation(summary = "연결 설정 생성",
            description = "드라이버 + 자격증명 + 리전을 묶어 연결 설정을 생성한다. "
                    + "ConfigName, ProviderName, DriverName, CredentialName, RegionName 필수")
    @PostMapping("/configs")
    public ApiResponse<?> createConnectionConfig(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(spiderClient.createConnectionConfig(body));
    }

    @Operation(summary = "연결 설정 목록 조회")
    @GetMapping("/configs")
    public ApiResponse<?> listConnectionConfigs() {
        return ApiResponse.ok(spiderClient.listConnectionConfigs());
    }

    @Operation(summary = "연결 설정 상세 조회")
    @GetMapping("/configs/{configName}")
    public ApiResponse<?> getConnectionConfig(@PathVariable String configName) {
        return ApiResponse.ok(spiderClient.getConnectionConfig(configName));
    }

    @Operation(summary = "연결 설정 삭제")
    @DeleteMapping("/configs/{configName}")
    public ApiResponse<Void> deleteConnectionConfig(@PathVariable String configName) {
        spiderClient.deleteConnectionConfig(configName);
        return ApiResponse.ok(null);
    }

    // ── 헬스체크 ────────────────────────────────────────────────

    @Operation(summary = "CB-Spider 상태 확인")
    @GetMapping("/health")
    public ApiResponse<?> healthCheck() {
        return ApiResponse.ok(spiderClient.healthCheck());
    }
}

package com.innogrid.aims.infra.controller;

import java.util.Map;

import com.innogrid.aims.cloudbarista.spider.SpiderClient;
import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.resource.dto.ResourceDto;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 네트워크 관리 API.
 * [요구사항 #30] IP Pool — CB-Spider VPC/서브넷 위임
 * [요구사항 #31] 라우팅 — CB-Spider 보안그룹 위임
 * [요구사항 #32] 토폴로지 — 향후 구현
 * [요구사항 #33] SAN Switch SFP — VictoriaMetrics SNMP 연동 (향후)
 */
@Tag(name = "네트워크", description = "VPC, 보안그룹, 키페어 (CB-Spider)")
@RestController
@RequestMapping("/api/v1/network")
public class NetworkController {

    private final SpiderClient spiderClient;

    public NetworkController(SpiderClient spiderClient) {
        this.spiderClient = spiderClient;
    }

    // ── VPC ──────────────────────────────────────────────────────

    @GetMapping("/vpcs")
    public ApiResponse<?> listVpcs(@RequestParam String connectionName) {
        return ApiResponse.ok(spiderClient.listVpcs(connectionName));
    }

    @GetMapping("/vpcs/{vpcName}")
    public ApiResponse<?> getVpc(@PathVariable String vpcName,
                                 @RequestParam String connectionName) {
        return ApiResponse.ok(spiderClient.getVpc(connectionName, vpcName));
    }

    @PostMapping("/vpcs")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.CreateVpcRequest.class)))
    public ApiResponse<?> createVpc(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(spiderClient.createVpc(request));
    }

    @DeleteMapping("/vpcs/{vpcName}")
    public ApiResponse<Void> deleteVpc(@PathVariable String vpcName,
                                       @RequestParam String connectionName) {
        spiderClient.deleteVpc(connectionName, vpcName);
        return ApiResponse.ok(null);
    }

    @PostMapping("/vpcs/{vpcName}/subnets")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.AddSubnetRequest.class)))
    public ApiResponse<?> addSubnet(@PathVariable String vpcName,
                                    @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(spiderClient.addSubnet(vpcName, request));
    }

    @DeleteMapping("/vpcs/{vpcName}/subnets/{subnetName}")
    public ApiResponse<Void> deleteSubnet(@PathVariable String vpcName,
                                          @PathVariable String subnetName,
                                          @RequestParam String connectionName) {
        spiderClient.deleteSubnet(connectionName, vpcName, subnetName);
        return ApiResponse.ok(null);
    }

    // ── 보안그룹 ────────────────────────────────────────────────

    @GetMapping("/security-groups")
    public ApiResponse<?> listSecurityGroups(@RequestParam String connectionName) {
        return ApiResponse.ok(spiderClient.listSecurityGroups(connectionName));
    }

    @PostMapping("/security-groups")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.CreateSecurityGroupRequest.class)))
    public ApiResponse<?> createSecurityGroup(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(spiderClient.createSecurityGroup(request));
    }

    @DeleteMapping("/security-groups/{sgName}")
    public ApiResponse<Void> deleteSecurityGroup(@PathVariable String sgName,
                                                 @RequestParam String connectionName) {
        spiderClient.deleteSecurityGroup(connectionName, sgName);
        return ApiResponse.ok(null);
    }

    @PostMapping("/security-groups/{sgName}/rules")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.AddSecurityRulesRequest.class)))
    public ApiResponse<?> addRules(@PathVariable String sgName,
                                   @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(spiderClient.addSecurityRules(sgName, request));
    }

    // ── 키페어 ──────────────────────────────────────────────────

    @GetMapping("/keypairs")
    public ApiResponse<?> listKeyPairs(@RequestParam String connectionName) {
        return ApiResponse.ok(spiderClient.listKeyPairs(connectionName));
    }

    @PostMapping("/keypairs")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = ResourceDto.CreateKeyPairRequest.class)))
    public ApiResponse<?> createKeyPair(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(spiderClient.createKeyPair(request));
    }

    @DeleteMapping("/keypairs/{keyPairName}")
    public ApiResponse<Void> deleteKeyPair(@PathVariable String keyPairName,
                                           @RequestParam String connectionName) {
        spiderClient.deleteKeyPair(connectionName, keyPairName);
        return ApiResponse.ok(null);
    }
}

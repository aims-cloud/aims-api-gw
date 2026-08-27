package com.innogrid.aims.infra.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.openstack.service.SubnetService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 서브넷 / IP주소 API.
 * OpenStack Neutron 서브넷·포트를 표출한다 (조회 전용).
 * 1~3세부 공통 OpenStack 기준. 최종 연동 대상은 1세부 OpenStack.
 */
@Tag(name = "서브넷/IP주소", description = "OpenStack Neutron 서브넷·IP 표출 (조회 전용)")
@RestController
@RequestMapping("/api/v1/network")
public class SubnetController {

    private final SubnetService subnetService;

    public SubnetController(SubnetService subnetService) {
        this.subnetService = subnetService;
    }

    // ── 서브넷 ──────────────────────────────────────────────────

    @Operation(summary = "서브넷 목록", description = "networkId(네트워크 구역)로 필터 가능")
    @GetMapping("/subnets")
    public ApiResponse<List<Map<String, Object>>> listSubnets(
            @RequestParam(required = false) String networkId) {
        return ApiResponse.ok(subnetService.listSubnets(networkId));
    }

    @Operation(summary = "서브넷 상세")
    @GetMapping("/subnets/{subnetId}")
    public ApiResponse<Map<String, Object>> getSubnet(@PathVariable String subnetId) {
        return ApiResponse.ok(subnetService.getSubnet(subnetId));
    }

    @Operation(summary = "서브넷 상세 — IP주소 탭")
    @GetMapping("/subnets/{subnetId}/ips")
    public ApiResponse<List<Map<String, Object>>> getSubnetIps(@PathVariable String subnetId) {
        return ApiResponse.ok(subnetService.getSubnetIps(subnetId));
    }

    // ── IP주소 ──────────────────────────────────────────────────

    @Operation(summary = "IP주소 목록", description = "네트워크구역(networkId)/서브넷(subnetId) 필터 지원")
    @GetMapping("/ips")
    public ApiResponse<List<Map<String, Object>>> listIps(
            @RequestParam(required = false) String networkId,
            @RequestParam(required = false) String subnetId) {
        return ApiResponse.ok(subnetService.listIps(networkId, subnetId));
    }

    @Operation(summary = "IP주소 상세", description = "포트 단위 상세 (연결 장비/MAC/할당 IP 목록)")
    @GetMapping("/ips/{portId}")
    public ApiResponse<Map<String, Object>> getIpDetail(@PathVariable String portId) {
        return ApiResponse.ok(subnetService.getIpDetail(portId));
    }
}

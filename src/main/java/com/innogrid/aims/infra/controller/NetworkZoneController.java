package com.innogrid.aims.infra.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.openstack.service.NetworkZoneService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 네트워크 구역 API.
 * OpenStack Neutron 네트워크를 '구역'으로 매핑하여 표출한다 (조회 전용).
 * 1~3세부 공통 OpenStack 기준. 최종 연동 대상은 1세부 OpenStack.
 */
@Tag(name = "네트워크 구역", description = "OpenStack Neutron 네트워크 표출 (조회 전용)")
@RestController
@RequestMapping("/api/v1/network/zones")
public class NetworkZoneController {

    private final NetworkZoneService zoneService;

    public NetworkZoneController(NetworkZoneService zoneService) {
        this.zoneService = zoneService;
    }

    @Operation(summary = "네트워크 구역 목록")
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listZones() {
        return ApiResponse.ok(zoneService.listZones());
    }

    @Operation(summary = "네트워크 구역 상세")
    @GetMapping("/{networkId}")
    public ApiResponse<Map<String, Object>> getZone(@PathVariable String networkId) {
        return ApiResponse.ok(zoneService.getZone(networkId));
    }

    @Operation(summary = "네트워크 구역 상세 — 서브넷 탭")
    @GetMapping("/{networkId}/subnets")
    public ApiResponse<List<Map<String, Object>>> getZoneSubnets(@PathVariable String networkId) {
        return ApiResponse.ok(zoneService.getZoneSubnets(networkId));
    }

    @Operation(summary = "네트워크 구역 상세 — 네트워크 인터페이스 탭")
    @GetMapping("/{networkId}/interfaces")
    public ApiResponse<List<Map<String, Object>>> getZoneInterfaces(@PathVariable String networkId) {
        return ApiResponse.ok(zoneService.getZoneInterfaces(networkId));
    }
}

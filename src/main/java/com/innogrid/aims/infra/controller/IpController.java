package com.innogrid.aims.infra.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.mock.MockDataProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * IP 관리 API.
 * [요구사항 #35] IP 관리 — MDC·퍼블릭 클라우드 연계 구간 IP 자원 할당·대역 통합 관리
 */
@Tag(name = "IP 관리", description = "IP 할당/회수")
@RestController
@RequestMapping("/api/v1/ips")
public class IpController {

    private final MockDataProvider mock;

    public IpController(MockDataProvider mock) {
        this.mock = mock;
    }

    /** IP 할당 현황 조회 */
    @GetMapping
    public ApiResponse<?> listIpAllocations(@RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(mock.getIpAllocations());
    }

    /** IP 할당 */
    @PostMapping
    public ApiResponse<?> allocateIp(@RequestBody Object request) {
        return ApiResponse.ok(null);
    }

    /** IP 회수 */
    @DeleteMapping("/{ipId}")
    public ApiResponse<Void> releaseIp(@PathVariable Long ipId) {
        return ApiResponse.ok(null);
    }

    /** IP 대역 정보 조회 */
    @GetMapping("/subnets")
    public ApiResponse<?> listSubnets(@RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(mock.getIpAllocations());
    }
}

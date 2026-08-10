package com.innogrid.aims.dashboard.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.mock.MockDataProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 대시보드 API — 각 도메인 데이터를 집계하여 대시보드용으로 제공.
 * [요구사항 #37] 네트워크 토폴로지 대시보드
 * [요구사항 #38] 인프라 토폴로지 대시보드
 * [요구사항 #39] 가상서버 그룹
 * [요구사항 #40] 사용자 대시보드
 * [요구사항 #41] 스토리지 대시보드
 * [요구사항 #42] 노드 현황
 * [요구사항 #43] GPU 대시보드
 * [요구사항 #44] 인프라 대시보드
 * [요구사항 #45] 가상서버 현황
 */
@Tag(name = "대시보드", description = "토폴로지, 노드, GPU, 인프라 대시보드")
@RestController
@RequestMapping("/api/v1/dashboards")
public class DashboardController {

    private final MockDataProvider mock;

    public DashboardController(MockDataProvider mock) {
        this.mock = mock;
    }

    /** 네트워크 토폴로지 대시보드 */
    @GetMapping("/topology/network")
    public ApiResponse<?> getNetworkTopologyDashboard() {
        return ApiResponse.ok(mock.getNetworkTopology());
    }

    /** 인프라 토폴로지 대시보드 */
    @GetMapping("/topology/infra")
    public ApiResponse<?> getInfraTopologyDashboard() {
        return ApiResponse.ok(mock.getNetworkTopology());
    }

    /** 가상서버 그룹 토폴로지 */
    @GetMapping("/topology/vm-groups")
    public ApiResponse<?> getVmGroupTopology() {
        return ApiResponse.ok(mock.getVmStatusDashboard());
    }

    /** 사용자 맞춤형 대시보드 */
    @GetMapping("/user")
    public ApiResponse<?> getUserDashboard() {
        return ApiResponse.ok(mock.getUserDashboard());
    }

    /** 스토리지 대시보드 */
    @GetMapping("/storage")
    public ApiResponse<?> getStorageDashboard() {
        return ApiResponse.ok(mock.getStorageDashboard());
    }

    /** 노드 현황 */
    @GetMapping("/nodes")
    public ApiResponse<?> getNodeStatusDashboard() {
        return ApiResponse.ok(mock.getNodeStatusDashboard());
    }

    /** GPU 대시보드 */
    @GetMapping("/gpu")
    public ApiResponse<?> getGpuDashboard() {
        return ApiResponse.ok(mock.getGpuDashboard());
    }

    /** 인프라 통합 대시보드 */
    @GetMapping("/infra")
    public ApiResponse<?> getInfraDashboard() {
        return ApiResponse.ok(mock.getInfraDashboard());
    }

    /** 가상서버 현황 */
    @GetMapping("/vm-status")
    public ApiResponse<?> getVmStatusDashboard() {
        return ApiResponse.ok(mock.getVmStatusDashboard());
    }
}

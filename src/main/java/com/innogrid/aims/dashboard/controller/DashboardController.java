package com.innogrid.aims.dashboard.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.dashboard.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "대시보드", description = "토폴로지, 노드, 인프라 대시보드")
@RestController
@RequestMapping("/api/v1/dashboards")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "네트워크 토폴로지")
    @GetMapping("/topology/network")
    public ApiResponse<?> getNetworkTopologyDashboard() {
        return ApiResponse.ok(dashboardService.getNetworkTopology());
    }

    @Operation(summary = "인프라 토폴로지")
    @GetMapping("/topology/infra")
    public ApiResponse<?> getInfraTopologyDashboard() {
        return ApiResponse.ok(dashboardService.getInfraDashboard());
    }

    @Operation(summary = "가상서버 그룹")
    @GetMapping("/topology/vm-groups")
    public ApiResponse<?> getVmGroupTopology() {
        return ApiResponse.ok(dashboardService.getVmStatusDashboard());
    }

    @Operation(summary = "사용자 대시보드")
    @GetMapping("/user")
    public ApiResponse<?> getUserDashboard() {
        return ApiResponse.ok(dashboardService.getUserDashboard());
    }

    @Operation(summary = "스토리지 대시보드")
    @GetMapping("/storage")
    public ApiResponse<?> getStorageDashboard() {
        return ApiResponse.ok(dashboardService.getStorageDashboard());
    }

    @Operation(summary = "노드 현황")
    @GetMapping("/nodes")
    public ApiResponse<?> getNodeStatusDashboard() {
        return ApiResponse.ok(dashboardService.getNodeStatusDashboard());
    }

    @Operation(summary = "인프라 통합 대시보드")
    @GetMapping("/infra")
    public ApiResponse<?> getInfraDashboard() {
        return ApiResponse.ok(dashboardService.getInfraDashboard());
    }

    @Operation(summary = "가상서버 현황")
    @GetMapping("/vm-status")
    public ApiResponse<?> getVmStatusDashboard() {
        return ApiResponse.ok(dashboardService.getVmStatusDashboard());
    }
}

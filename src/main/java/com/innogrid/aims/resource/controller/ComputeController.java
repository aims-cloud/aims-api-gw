package com.innogrid.aims.resource.controller;

import com.innogrid.aims.cloudbarista.spider.SpiderClient;
import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.service.MonitoringService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 컴퓨팅 관리 API.
 * [요구사항 #23] 연산 자원 모니터링(CPU/MEM) → VictoriaMetrics
 * [요구사항 #24] AI 반도체 관리(GPU/NPU) → VictoriaMetrics (dcgm_exporter)
 * [요구사항 #26] 성능 모니터링 → VictoriaMetrics
 * VM 스펙 조회 → CB-Spider
 *
 * 응답 DTO: RealtimeUsageResponse, GpuStatusResponse, NodeStatusResponse
 */
@Tag(name = "컴퓨팅", description = "CPU/MEM/GPU 모니터링, VM 스펙 조회")
@RestController
@RequestMapping("/api/v1/compute")
public class ComputeController {

    private final MonitoringService monitoringService;
    private final SpiderClient spiderClient;

    public ComputeController(MonitoringService monitoringService, SpiderClient spiderClient) {
        this.monitoringService = monitoringService;
        this.spiderClient = spiderClient;
    }

    /** CPU/MEM/Disk 실시간 사용률 */
    @GetMapping("/usage")
    public ApiResponse<?> getComputeUsage(@RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(monitoringService.getRealtimeUsage(mdcId));
    }

    /** GPU 실시간 상태 */
    @GetMapping("/gpu")
    public ApiResponse<?> getGpuStatus(@RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(monitoringService.getGpuStatus(mdcId));
    }

    /** 노드 성능 상세 */
    @GetMapping("/nodes/{nodeId}/performance")
    public ApiResponse<?> getNodePerformance(@PathVariable String nodeId) {
        return ApiResponse.ok(monitoringService.getNodePerformance(nodeId));
    }

    /** VM 스펙 목록 (퍼블릭 → CB-Spider) */
    @GetMapping("/specs")
    public ApiResponse<?> listVmSpecs(@RequestParam String connectionName) {
        return ApiResponse.ok(spiderClient.listVMSpecs(connectionName));
    }
}

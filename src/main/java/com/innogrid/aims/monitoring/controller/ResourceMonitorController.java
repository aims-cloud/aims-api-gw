package com.innogrid.aims.monitoring.controller;

import java.time.Instant;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.service.MonitoringService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 리소스 사용률 모니터링 API.
 * [요구사항 #46] CPU·메모리·스토리지·네트워크 실시간/추이 모니터링
 * 데이터 출처: VictoriaMetrics (PromQL)
 *
 * 응답 DTO: RealtimeUsageResponse, MetricTrendResponse
 */
@Tag(name = "리소스 모니터링", description = "실시간/추이 사용률, GPU, 노드 성능")
@RestController
@RequestMapping("/api/v1/monitoring/resources")
public class ResourceMonitorController {

    private final MonitoringService monitoringService;

    public ResourceMonitorController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    /** 실시간 리소스 사용률 (CPU/MEM/Disk/Network) */
    @GetMapping("/realtime")
    public ApiResponse<?> getRealtimeUsage(@RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(monitoringService.getRealtimeUsage(mdcId));
    }

    /** CPU 사용률 추이 */
    @GetMapping("/trend/cpu")
    public ApiResponse<?> getCpuTrend(@RequestParam(required = false) String mdcId,
                                      @RequestParam String from,
                                      @RequestParam String to) {
        return ApiResponse.ok(monitoringService.getUsageTrend(
                mdcId, Instant.parse(from), Instant.parse(to)));
    }

    /** GPU 사용률 실시간 */
    @GetMapping("/gpu")
    public ApiResponse<?> getGpuStatus(@RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(monitoringService.getGpuStatus(mdcId));
    }

    /** GPU 사용률 추이 */
    @GetMapping("/trend/gpu")
    public ApiResponse<?> getGpuTrend(@RequestParam(required = false) String mdcId,
                                      @RequestParam String from,
                                      @RequestParam String to) {
        return ApiResponse.ok(monitoringService.getGpuTrend(
                mdcId, Instant.parse(from), Instant.parse(to)));
    }

    /** 노드 성능 상세 (load, 프로세스, CPU/MEM/Disk) */
    @GetMapping("/nodes/{nodeId}")
    public ApiResponse<?> getNodePerformance(@PathVariable String nodeId) {
        return ApiResponse.ok(monitoringService.getNodePerformance(nodeId));
    }
}

package com.innogrid.aims.monitoring.controller;

import java.time.Instant;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.service.MonitoringService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 물리 자산 모니터링 API (MDC 전용).
 * [요구사항 #53] IPMI·SNMP 기반 하드웨어 상태, 전력, 팬 속도, 온도 모니터링
 * 데이터 출처: VictoriaMetrics (ipmi_exporter / snmp_exporter 메트릭)
 *
 * 응답 DTO: PhysicalServerResponse, MetricTrendResponse
 */
@Tag(name = "물리 자산 모니터링", description = "IPMI 전력/온도/팬 모니터링")
@RestController
@RequestMapping("/api/v1/monitoring/physical")
public class PhysicalMonitorController {

    private final MonitoringService monitoringService;

    public PhysicalMonitorController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    /** 물리 서버 상세 (온도, 팬, 전력) */
    @GetMapping("/{serverId}")
    public ApiResponse<?> getPhysicalServerStatus(@PathVariable String serverId) {
        return ApiResponse.ok(monitoringService.getPhysicalServerStatus(serverId));
    }

    /** 전력 소모 추이 */
    @GetMapping("/power/trend")
    public ApiResponse<?> getPowerTrend(@RequestParam(required = false) String mdcId,
                                        @RequestParam String from,
                                        @RequestParam String to) {
        return ApiResponse.ok(monitoringService.getPowerTrend(
                mdcId, Instant.parse(from), Instant.parse(to)));
    }

    /** 온도 추이 */
    @GetMapping("/temperature/trend")
    public ApiResponse<?> getTemperatureTrend(@RequestParam(required = false) String mdcId,
                                              @RequestParam String from,
                                              @RequestParam String to) {
        return ApiResponse.ok(monitoringService.getTemperatureTrend(
                mdcId, Instant.parse(from), Instant.parse(to)));
    }
}

package com.innogrid.aims.metering.controller;

import java.time.OffsetDateTime;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.metering.service.MeteringAggregationService;
import com.innogrid.aims.metering.service.MeteringQueryService;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 미터링 API.
 * [요구사항 #9]  리소스 사용량 — 집계 데이터 조회
 * [요구사항 #10] 통합 보고서 — 일간 요약
 * 데이터 출처: metering_hourly 테이블 (VictoriaMetrics에서 매시간 집계)
 */
@Tag(name = "미터링", description = "리소스 사용량 집계 조회, 보고서, 저장 상태")
@RestController
@RequestMapping("/api/v1/metering")
public class MeteringController {

    private final MeteringQueryService queryService;
    private final MeteringAggregationService aggregationService;
    private final VictoriaMetricsClient vmClient;

    public MeteringController(MeteringQueryService queryService,
                              MeteringAggregationService aggregationService,
                              VictoriaMetricsClient vmClient) {
        this.queryService = queryService;
        this.aggregationService = aggregationService;
        this.vmClient = vmClient;
    }

    // ── 집계 데이터 조회 ────────────────────────────────────────

    @Operation(summary = "시간별 리소스 사용량 조회", description = "metering_hourly 테이블에서 조회")
    @GetMapping("/usage")
    public ApiResponse<?> getResourceUsage(@RequestParam(required = false) String mdcId,
                                           @RequestParam(required = false) String metricName,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        OffsetDateTime fromDt = from != null ? OffsetDateTime.parse(from) : OffsetDateTime.now().minusDays(7);
        OffsetDateTime toDt = to != null ? OffsetDateTime.parse(to) : OffsetDateTime.now();
        return ApiResponse.ok(queryService.getUsage(mdcId, metricName, fromDt, toDt));
    }

    @Operation(summary = "일간 요약 보고서", description = "날짜별 메트릭 평균/최대/최소 집계")
    @GetMapping("/daily-summary")
    public ApiResponse<?> getDailySummary(@RequestParam(required = false) String mdcId,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        OffsetDateTime fromDt = from != null ? OffsetDateTime.parse(from) : OffsetDateTime.now().minusDays(30);
        OffsetDateTime toDt = to != null ? OffsetDateTime.parse(to) : OffsetDateTime.now();
        return ApiResponse.ok(queryService.getDailySummary(mdcId, fromDt, toDt));
    }

    // ── 저장 상태 ───────────────────────────────────────────────

    @Operation(summary = "메트릭 저장소 상태", description = "PostgreSQL 집계 레코드 수, VictoriaMetrics 상태")
    @GetMapping("/storage-status")
    public ApiResponse<?> getStorageStatus() {
        var status = queryService.getStorageStatus();
        status.put("victoriaMetricsHealthy", vmClient.isHealthy());
        return ApiResponse.ok(status);
    }

    // ── 수동 집계 ───────────────────────────────────────────────

    @Operation(summary = "수동 집계 트리거", description = "지난 1시간 데이터를 즉시 집계합니다")
    @PostMapping("/aggregate")
    public ApiResponse<Void> triggerAggregation() {
        aggregationService.aggregateHourly();
        return ApiResponse.ok(null);
    }

    @Operation(summary = "오래된 데이터 정리", description = "13개월 이전 데이터를 즉시 삭제합니다")
    @PostMapping("/cleanup")
    public ApiResponse<Void> triggerCleanup() {
        aggregationService.cleanupOldData();
        return ApiResponse.ok(null);
    }
}

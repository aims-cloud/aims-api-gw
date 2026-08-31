package com.innogrid.aims.monitoring.controller;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.agent.RegisterAgentRequest;
import com.innogrid.aims.monitoring.entity.MonitoringTarget;
import com.innogrid.aims.monitoring.entity.MonitoringTarget.ExporterType;
import com.innogrid.aims.monitoring.service.MetricCatalogService;
import com.innogrid.aims.monitoring.service.PrometheusTargetService;
import com.innogrid.aims.monitoring.service.ResourceCollectorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 모니터링 에이전트(수집 대상) 관리 API.
 * [요구사항 #52] 모니터링 에이전트 — 배포·상태·버전 통합 관리
 */
@Tag(name = "모니터링 에이전트", description = "수집 대상 등록/상태 관리, Prometheus 타겟 관리, 메트릭 카탈로그")
@RestController
@RequestMapping("/api/v1/monitoring/agents")
public class AgentController {

    private final ResourceCollectorService collectorService;
    private final PrometheusTargetService prometheusTargetService;
    private final MetricCatalogService metricCatalogService;

    public AgentController(ResourceCollectorService collectorService,
                           PrometheusTargetService prometheusTargetService,
                           MetricCatalogService metricCatalogService) {
        this.collectorService = collectorService;
        this.prometheusTargetService = prometheusTargetService;
        this.metricCatalogService = metricCatalogService;
    }

    // ── 수집 대상 CRUD ──────────────────────────────────────────

    @Operation(summary = "수집 대상 목록 조회")
    @GetMapping
    public ApiResponse<?> listAgents(@RequestParam(required = false) String mdcId) {
        return ApiResponse.ok(collectorService.listTargets(mdcId));
    }

    @Operation(summary = "수집 대상 상세 조회")
    @GetMapping("/{agentId}")
    public ApiResponse<?> getAgent(@PathVariable Long agentId) {
        return ApiResponse.ok(collectorService.getTarget(agentId));
    }

    @Operation(summary = "수집 대상 등록", description = "등록 후 Prometheus가 자동으로 수집을 시작합니다")
    @PostMapping
    public ApiResponse<?> registerAgent(@RequestBody RegisterAgentRequest request) {
        MonitoringTarget target = collectorService.registerTarget(
                request.mdcId(), request.hostname(), request.address(),
                request.port(), request.exporterType());
        return ApiResponse.ok(target);
    }

    @Operation(summary = "수집 대상 별칭 설정", description = "토폴로지 별칭표기 토글에 사용. 빈값이면 별칭 해제")
    @PutMapping("/{agentId}/alias")
    public ApiResponse<?> updateAlias(@PathVariable Long agentId,
                                      @RequestBody java.util.Map<String, String> request) {
        return ApiResponse.ok(collectorService.updateAlias(agentId, request.get("alias")));
    }

    @Operation(summary = "수집 대상 삭제")
    @DeleteMapping("/{agentId}")
    public ApiResponse<Void> removeAgent(@PathVariable Long agentId) {
        collectorService.removeTarget(agentId);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "에이전트 상태 수동 갱신", description = "VictoriaMetrics의 up 메트릭으로 상태 갱신")
    @PostMapping("/refresh")
    public ApiResponse<Void> refreshStatus() {
        collectorService.refreshAgentStatus();
        return ApiResponse.ok(null);
    }

    // ── Prometheus 타겟 ─────────────────────────────────────────

    @Operation(summary = "Prometheus file_sd 타겟 JSON 조회",
               description = "현재 등록된 수집 대상을 Prometheus file_sd 형식으로 반환")
    @GetMapping("/prometheus-targets")
    public ApiResponse<?> getPrometheusTargets() {
        return ApiResponse.ok(prometheusTargetService.getTargetsAsFileSd());
    }

    @Operation(summary = "Prometheus 타겟 파일 수동 갱신")
    @PostMapping("/prometheus-targets/export")
    public ApiResponse<Void> exportPrometheusTargets() {
        prometheusTargetService.exportTargets();
        return ApiResponse.ok(null);
    }

    // ── 메트릭 카탈로그 ─────────────────────────────────────────

    @Operation(summary = "전체 메트릭 카탈로그 조회", description = "Exporter 유형별 수집 가능한 메트릭 목록")
    @GetMapping("/metrics-catalog")
    public ApiResponse<?> getMetricsCatalog() {
        return ApiResponse.ok(metricCatalogService.getAllMetrics());
    }

    @Operation(summary = "Exporter 유형별 메트릭 카탈로그")
    @GetMapping("/metrics-catalog/{exporterType}")
    public ApiResponse<?> getMetricsByType(@PathVariable ExporterType exporterType) {
        return ApiResponse.ok(metricCatalogService.getMetrics(exporterType));
    }

}

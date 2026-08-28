package com.innogrid.aims.dashboard.controller;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.dashboard.dto.GpuDashboardFilter;
import com.innogrid.aims.dashboard.dto.GpuDashboardResponse;
import com.innogrid.aims.dashboard.dto.GpuDistributionResponse;
import com.innogrid.aims.dashboard.dto.GpuFilterOptionsResponse;
import com.innogrid.aims.dashboard.dto.GpuMetric;
import com.innogrid.aims.dashboard.dto.GpuRankingResponse;
import com.innogrid.aims.dashboard.dto.GpuSummaryResponse;
import com.innogrid.aims.dashboard.dto.GpuThresholdResponse;
import com.innogrid.aims.dashboard.dto.GpuTrendResponse;
import com.innogrid.aims.dashboard.dto.UpdateGpuThresholdRequest;
import com.innogrid.aims.dashboard.service.GpuDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 2026.08.28
 * TODO:: 세부 1의 GPU 메트릭 및 Thanos 연동 규격이 확정되지 않아 현재 MockDataProvider를 사용한다.
 *        연동 규격 확정 후 Mock 분기와 GPU Mock 데이터를 제거하고 Thanos 조회 경로로 전환한다.
 *
 * TODO:: 대시보드 상단 필터의 데이터 출처와 Thanos 레이블 매핑 정책을 확정해야 한다.
 *        역할, 배포 영역 및 가상머신 정보는 포탈의 자원 관리 데이터를 기준으로 조회하고,
 *        vmId 또는 vmName을 Thanos 레이블과 연결하여 메트릭을 필터링하는 방안을 검토한다.
 *        유형(PassThrough, MIG, vGPU)은 GPU 모델 필터(gpuTypes)와 구분하여 별도 파라미터로 제공할지 결정한다.
 */
@Tag(name = "GPU 대시보드", description = "GPU 대시보드 현황 및 모니터링")
@RestController
@RequestMapping("/api/v1/dashboards/gpu")
public class GpuDashboardController {

    private final GpuDashboardService gpuDashboardService;

    public GpuDashboardController(GpuDashboardService gpuDashboardService) {
        this.gpuDashboardService = gpuDashboardService;
    }

    @Operation(summary = "GPU 대시보드 통합 조회", description = "대시보드 최초 로딩에 필요한 데이터를 통합 조회합니다.")
    @GetMapping
    public ApiResponse<GpuDashboardResponse> getGpuDashboard(
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        // TODO:: 실제 Thanos 연동 시 from/to 미입력에 대한 기본 조회 기간 및 타임존 정책 적용
        return ApiResponse.ok(gpuDashboardService.getDashboard(filter(role, deploymentId, vmId, gpuTypes), from, to));
    }

    @Operation(summary = "GPU 대시보드 필터 목록 조회")
    @GetMapping("/filter-options")
    public ApiResponse<GpuFilterOptionsResponse> getFilterOptions() {
        return ApiResponse.ok(gpuDashboardService.getFilterOptions());
    }

    @Operation(summary = "GPU 대시보드 요약 조회", description = "할당·유휴·고활용 GPU와 VRAM 사용률 및 Peak VRAM을 조회합니다.")
    @GetMapping("/summary")
    public ApiResponse<GpuSummaryResponse> getSummary(
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes) {
        return ApiResponse.ok(gpuDashboardService.getSummary(filter(role, deploymentId, vmId, gpuTypes)));
    }

    @Operation(summary = "VM 기준 GPU 사용률 분포 조회")
    @GetMapping("/distributions/vms")
    public ApiResponse<List<GpuDistributionResponse>> getVmDistribution(
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes) {
        return ApiResponse.ok(gpuDashboardService.getDistribution(filter(role, deploymentId, vmId, gpuTypes), true));
    }

    @Operation(summary = "GPU 유형별 사용률 분포 조회")
    @GetMapping("/distributions/types")
    public ApiResponse<List<GpuDistributionResponse>> getGpuTypeDistribution(
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes) {
        return ApiResponse.ok(gpuDashboardService.getDistribution(filter(role, deploymentId, vmId, gpuTypes), false));
    }

    @Operation(summary = "VM Top N 조회")
    @GetMapping("/rankings/vms")
    public ApiResponse<List<GpuRankingResponse>> getVmRankings(
            @RequestParam GpuMetric metric,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes) {
        return ApiResponse.ok(gpuDashboardService.getRankings(
                filter(role, deploymentId, vmId, gpuTypes), metric, limit, true));
    }

    @Operation(summary = "GPU 할당 단위 Top N 조회")
    @GetMapping("/rankings/gpus")
    public ApiResponse<List<GpuRankingResponse>> getGpuRankings(
            @RequestParam GpuMetric metric,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes) {
        return ApiResponse.ok(gpuDashboardService.getRankings(
                filter(role, deploymentId, vmId, gpuTypes), metric, limit, false));
    }

    @Operation(summary = "VM Top N 시계열 추이 조회")
    @GetMapping("/trends/vms")
    public ApiResponse<List<GpuTrendResponse>> getVmTrends(
            @RequestParam GpuMetric metric,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int limit,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes) {
        return ApiResponse.ok(gpuDashboardService.getTrends(
                filter(role, deploymentId, vmId, gpuTypes), metric, limit, from, to, true));
    }

    @Operation(summary = "GPU 할당 단위 Top N 시계열 추이 조회")
    @GetMapping("/trends/gpus")
    public ApiResponse<List<GpuTrendResponse>> getGpuTrends(
            @RequestParam GpuMetric metric,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int limit,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes) {
        return ApiResponse.ok(gpuDashboardService.getTrends(
                filter(role, deploymentId, vmId, gpuTypes), metric, limit, from, to, false));
    }

    @Operation(summary = "GPU 판정 임계치 조회")
    @GetMapping("/thresholds")
    public ApiResponse<GpuThresholdResponse> getThresholds() {
        return ApiResponse.ok(gpuDashboardService.getThresholds());
    }

    @Operation(summary = "GPU 판정 임계치 변경")
    @PutMapping("/thresholds")
    public ApiResponse<GpuThresholdResponse> updateThresholds(@Valid @RequestBody UpdateGpuThresholdRequest request) {
        return ApiResponse.ok(gpuDashboardService.updateThresholds(request));
    }

    private GpuDashboardFilter filter(List<String> roles, List<String> deploymentIds,
                                      List<String> vmIds, List<String> gpuTypes) {
        // TODO:: 실제 Thanos 연동 시 파라미터를 운영 Prometheus 레이블 및 자원 관리 DB 식별자와 매핑
        return new GpuDashboardFilter(roles, deploymentIds, vmIds, gpuTypes);
    }
}

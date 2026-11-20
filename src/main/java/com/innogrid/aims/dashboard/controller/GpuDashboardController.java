package com.innogrid.aims.dashboard.controller;

import java.time.OffsetDateTime;
import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.dashboard.dto.GpuDashboardFilter;
import com.innogrid.aims.dashboard.dto.GpuDashboardResponse;
import com.innogrid.aims.dashboard.dto.GpuDistributionResponse;
import com.innogrid.aims.dashboard.dto.GpuFilterOptionsResponse;
import com.innogrid.aims.dashboard.dto.GpuMetric;
import com.innogrid.aims.dashboard.dto.GpuRankingResponse;
import com.innogrid.aims.dashboard.dto.GpuSummaryResponse;
import com.innogrid.aims.dashboard.dto.GpuThresholdResponse;
import com.innogrid.aims.dashboard.dto.GpuTrendResponse;
import com.innogrid.aims.dashboard.dto.GpuUnitPointResponse;
import com.innogrid.aims.dashboard.dto.UpdateGpuThresholdRequest;
import com.innogrid.aims.dashboard.service.GpuDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GPU 대시보드 API (Confluence 2320072739 — 피그마 Section 8·요구사항 기준).
 *
 * 공통 조회 조건: role(토큰 역할 안에서 좁힘)·deploymentId(MDC)·vmId·gpuTypes(모델명, 계약 유지)·allocationTypes(할당 유형
 * PASSTHROUGH·MIG·VGPU, 피그마 "유형")와 기간 from·to(ISO-8601, 기본 최근 1시간, 상한 30일 — 넘으면 400).
 * 사용률·VRAM 은 기간 평균, Peak VRAM 은 기간 최대다. 실데이터 모드의 VM·PASSTHROUGH/VGPU 정보는 3단계 매핑 원장 이후 채워진다.
 *
 * 포털 로그(docs/08 §10.1 묶음 7): 임계값 변경은 {@code @PortalLog(AUDIT)}, 화면 진입 시 한 번 부르는 필터 옵션·임계값 조회는 {@code ACCESS}.
 * 본문·요약·분포·순위·추이는 화면이 60초마다 자동 갱신하므로 남기지 않는다(docs/08 §2 ACCESS 제외 기준).
 *
 * TODO:: 실데이터 검증이 끝나면 aims.gpu-dashboard.mock-enabled 와 MockDataProvider 분기 제거
 */
@Tag(name = "GPU 대시보드", description = "GPU 대시보드 현황 및 모니터링")
@RestController
@RequestMapping("/api/v1/dashboards/gpu")
public class GpuDashboardController {

    private final GpuDashboardService gpuDashboardService;

    public GpuDashboardController(GpuDashboardService gpuDashboardService) {
        this.gpuDashboardService = gpuDashboardService;
    }

    @Operation(summary = "GPU 대시보드 통합 조회", description = "대시보드 최초 로딩에 필요한 데이터를 통합 조회합니다. "
            + "실데이터 모드에서는 토큰 역할 스코프 안의 DCGM 수집 대상(GPU 노드)만 보이며, role 은 토큰 역할 안에서만 좁히고 "
            + "deploymentId 는 노드 자산의 MDC 입니다. nodeCoverage 는 등록 노드 수·수집 중 노드 수·노드별 수집 여부이고, "
            + "unregisteredNodeCount(Thanos 에 있으나 수집 대상 원장에 없는 노드 수)는 ADMIN 토큰에만 값이 있습니다. "
            + "gpuDistribution 은 GPU 할당 단위 점(수집 끊긴 GPU 는 값 null), GPU Top5 는 사용률·VRAM 사용률·VRAM Used·Peak VRAM 4종입니다.")
    @GetMapping
    public ApiResponse<GpuDashboardResponse> getGpuDashboard(
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes,
            @RequestParam(required = false) List<String> allocationTypes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.ok(gpuDashboardService.getDashboard(
                filter(role, deploymentId, vmId, gpuTypes, allocationTypes), from, to));
    }

    @Operation(summary = "GPU 대시보드 필터 목록 조회",
            description = "roles 는 토큰 역할 전체, deploymentIds·deployments 는 role 을 주면 그 역할들이 가진 배포영역(MDC)으로 좁힙니다. "
                    + "deployments 의 name 은 MDC 원장이 없어 mdcId 와 같습니다. allocationTypes 는 PASSTHROUGH·MIG·VGPU 입니다.")
    @PortalLog(type = PortalLogType.ACCESS, screen = "GPU 대시보드", action = "조회",
               content = "'GPU 대시보드 필터 옵션 조회 (역할: ' + (#role ?: '전체') + ')'")
    @GetMapping("/filter-options")
    public ApiResponse<GpuFilterOptionsResponse> getFilterOptions(@RequestParam(required = false) List<String> role) {
        return ApiResponse.ok(gpuDashboardService.getFilterOptions(role));
    }

    @Operation(summary = "GPU 대시보드 요약 조회",
            description = "활성·유휴·고활용·할당 GPU 수, VRAM 사용률·사용량, Peak VRAM 과 그 GPU·VM. 판정은 기간에 표본이 있는 GPU 만: "
                    + "활성 사용률 > active, 유휴 < idle(같으면 어느 쪽도 아님), 고활용 사용률 ≥ high AND VRAM ≥ highVram.")
    @GetMapping("/summary")
    public ApiResponse<GpuSummaryResponse> getSummary(
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes,
            @RequestParam(required = false) List<String> allocationTypes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.ok(gpuDashboardService.getSummary(filter(role, deploymentId, vmId, gpuTypes, allocationTypes), from, to));
    }

    @Operation(summary = "VM 기준 GPU 사용률 분포 조회", description = "점 = VM, 값은 할당 GPU 평균(표본 없는 GPU 제외, gpuCount 에는 포함).")
    @GetMapping("/distributions/vms")
    public ApiResponse<List<GpuDistributionResponse>> getVmDistribution(
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes,
            @RequestParam(required = false) List<String> allocationTypes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.ok(gpuDashboardService.getDistribution(
                filter(role, deploymentId, vmId, gpuTypes, allocationTypes), true, from, to));
    }

    @Operation(summary = "GPU 모델명별 사용률 분포 조회", description = "점 = GPU 모델명 집계(계약 유지). 피그마의 GPU 할당 단위 분포는 /distributions/gpus.")
    @GetMapping("/distributions/types")
    public ApiResponse<List<GpuDistributionResponse>> getGpuTypeDistribution(
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes,
            @RequestParam(required = false) List<String> allocationTypes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.ok(gpuDashboardService.getDistribution(
                filter(role, deploymentId, vmId, gpuTypes, allocationTypes), false, from, to));
    }

    @Operation(summary = "GPU 할당 단위 기준 분포 조회",
            description = "점 = GPU 할당 단위(PassThrough·vGPU 는 물리 GPU, MIG 는 인스턴스). 수집 끊긴 GPU 는 사용률 null·collecting=false.")
    @GetMapping("/distributions/gpus")
    public ApiResponse<List<GpuUnitPointResponse>> getGpuUnitDistribution(
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes,
            @RequestParam(required = false) List<String> allocationTypes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.ok(gpuDashboardService.getGpuDistribution(
                filter(role, deploymentId, vmId, gpuTypes, allocationTypes), from, to));
    }

    @Operation(summary = "VM Top N 조회")
    @GetMapping("/rankings/vms")
    public ApiResponse<List<GpuRankingResponse>> getVmRankings(
            @RequestParam GpuMetric metric,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes,
            @RequestParam(required = false) List<String> allocationTypes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.ok(gpuDashboardService.getRankings(
                filter(role, deploymentId, vmId, gpuTypes, allocationTypes), metric, limit, true, from, to));
    }

    @Operation(summary = "GPU 할당 단위 Top N 조회", description = "수집 끊긴 GPU 는 순위에서 빠집니다. 메타에 roleName·deploymentId 가 있습니다.")
    @GetMapping("/rankings/gpus")
    public ApiResponse<List<GpuRankingResponse>> getGpuRankings(
            @RequestParam GpuMetric metric,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes,
            @RequestParam(required = false) List<String> allocationTypes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.ok(gpuDashboardService.getRankings(
                filter(role, deploymentId, vmId, gpuTypes, allocationTypes), metric, limit, false, from, to));
    }

    @Operation(summary = "VM Top N 시계열 추이 조회", description = "순위와 같은 기간·같은 VM 집합. 간격은 기간/60(최소 15초, 최대 1시간).")
    @GetMapping("/trends/vms")
    public ApiResponse<List<GpuTrendResponse>> getVmTrends(
            @RequestParam GpuMetric metric,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes,
            @RequestParam(required = false) List<String> allocationTypes) {
        return ApiResponse.ok(gpuDashboardService.getTrends(
                filter(role, deploymentId, vmId, gpuTypes, allocationTypes), metric, limit, from, to, true));
    }

    @Operation(summary = "GPU 할당 단위 Top N 시계열 추이 조회", description = "순위와 같은 기간·같은 GPU 집합. 간격은 기간/60(최소 15초, 최대 1시간).")
    @GetMapping("/trends/gpus")
    public ApiResponse<List<GpuTrendResponse>> getGpuTrends(
            @RequestParam GpuMetric metric,
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) List<String> deploymentId,
            @RequestParam(required = false) List<String> vmId,
            @RequestParam(required = false) List<String> gpuTypes,
            @RequestParam(required = false) List<String> allocationTypes) {
        return ApiResponse.ok(gpuDashboardService.getTrends(
                filter(role, deploymentId, vmId, gpuTypes, allocationTypes), metric, limit, from, to, false));
    }

    @Operation(summary = "GPU 판정 임계치 조회",
            description = "전역 공용(DB 영속). 활성: 사용률 > active, 유휴: 사용률 < idle, 고활용: 사용률 ≥ high AND VRAM ≥ highVram.")
    @PortalLog(type = PortalLogType.ACCESS, screen = "GPU 대시보드", action = "조회",
               content = "'GPU 임계값 조회'")
    @GetMapping("/thresholds")
    public ApiResponse<GpuThresholdResponse> getThresholds() {
        return ApiResponse.ok(gpuDashboardService.getThresholds());
    }

    @Operation(summary = "GPU 판정 임계치 변경",
            description = "임계값 팝업 3종(활성·유휴·고활용)의 적용. 모든 사용자에게 공용으로 반영됩니다. "
                    + "activeGpuUtilization 을 생략하면 현재 값을 유지합니다. 유휴가 고활용 사용률보다 크면 400.")
    @PortalLog(screen = "GPU 대시보드", action = "임계값 변경",
            content = "'GPU 임계값 변경: 활성 ' + #request.activeGpuUtilization + ', 유휴 ' + #request.idleGpuUtilization"
                    + " + ', 고활용 ' + #request.highGpuUtilization + '/' + #request.highVramUtilization")
    @PutMapping("/thresholds")
    public ApiResponse<GpuThresholdResponse> updateThresholds(@Valid @RequestBody UpdateGpuThresholdRequest request) {
        return ApiResponse.ok(gpuDashboardService.updateThresholds(request));
    }

    private GpuDashboardFilter filter(List<String> roles, List<String> deploymentIds, List<String> vmIds,
                                      List<String> gpuTypes, List<String> allocationTypes) {
        return new GpuDashboardFilter(roles, deploymentIds, vmIds, gpuTypes, allocationTypes);
    }
}

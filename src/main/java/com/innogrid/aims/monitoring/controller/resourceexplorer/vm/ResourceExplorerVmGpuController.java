package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuAllocationResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuInstanceInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.gpu.VmGpuInstanceListItemResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerVmGpuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > VM GPU 카테고리 패널 API.
 *
 * 그룹(트리에서 VM 하위 GPU 카테고리 선택)과 개별 GPU 인스턴스(하위 장치 선택)의
 * 개요·모니터링 탭을 하나의 컨트롤러에서 제공한다.
 *   그룹 개요        — KPI 카드 6종, GPU 인스턴스 목록 테이블
 *   개별 인스턴스 개요 — KPI 카드 6종, 가상머신 할당 정보 표, GPU 기본 정보 표
 *   모니터링         — 지표별 시계열 (화면 설계 확정 전, 잠정 지표 세트)
 * VM 은 nsId·infraId·nodeId 쿼리 파라미터로 식별한다(다른 VM 패널과 동일). VM↔GPU 인스턴스 할당 원장이 없어
 * 데이터 구현은 후순위이며 현재 전부 501 이다.
 * 노드의 물리 GPU 패널은 ResourceExplorerNodeGpuController, VM 상세는 ResourceExplorerVmController 가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (VM GPU)",
        description = "VM GPU 카테고리 — 그룹/개별 인스턴스 KPI, 인스턴스 목록, 할당·기본 정보, 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/vm/gpu")
public class ResourceExplorerVmGpuController {

    private final ResourceExplorerVmGpuService resourceExplorerVmGpuService;

    public ResourceExplorerVmGpuController(ResourceExplorerVmGpuService resourceExplorerVmGpuService) {
        this.resourceExplorerVmGpuService = resourceExplorerVmGpuService;
    }

    // ── 그룹 (GPU 카테고리 선택) ────────────────────────────────

    @Operation(summary = "VM GPU 그룹 개요 KPI",
            description = "그룹 개요 탭 카드 6종 — 총 GPU 수, 최대/평균 GPU Utilization, "
                    + "평균/최대 GPU Memory Utilization, 최근 알람 수를 조회합니다. (구현 전 — 501)")
    @GetMapping("/summary")
    public ApiResponse<VmGpuGroupSummaryResponse> getGroupSummary(@RequestParam(required = false) String roleName,
                                                                  @RequestParam String nsId,
                                                                  @RequestParam String infraId,
                                                                  @RequestParam String nodeId) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getGroupSummary(roleName, nsId, infraId, nodeId));
    }

    @Operation(summary = "GPU 인스턴스 목록",
            description = "그룹 개요 탭 목록 — 인스턴스/타입(PassThrough|MIG|vGPU)/GPU util(현재/평균)/"
                    + "GPU Mem util(현재/평균)/VRAM(사용/할당)/수집 상태/최근 알람 수. "
                    + "컬럼 정렬과 페이징을 지원합니다. page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다. (구현 전 — 501)")
    @GetMapping("/instances")
    public ApiResponse<PageResponse<VmGpuInstanceListItemResponse>> getInstances(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String nodeId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "instanceId,asc") String sort) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getInstances(roleName, nsId, infraId, nodeId, page, size, sort));
    }

    @Operation(summary = "VM GPU 그룹 모니터링 차트",
            description = "그룹 모니터링 탭 — metric 별 인스턴스 분해 시계열. interval 은 조회 기간(지금 기준)입니다. "
                    + "화면 설계 확정 전으로 지표 세트가 변경될 수 있습니다. (구현 전 — 501)")
    @GetMapping("/metrics/trend")
    public ApiResponse<GpuMetricTrendResponse> getGroupMetricTrend(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String nodeId,
            @RequestParam(defaultValue = "GPU_UTILIZATION") GpuMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getGroupMetricTrend(roleName, nsId, infraId, nodeId, metric, interval));
    }

    // ── 개별 GPU 인스턴스 (GPU 장치 선택) ───────────────────────

    @Operation(summary = "GPU 인스턴스 개요 KPI",
            description = "개별 인스턴스 개요 탭 카드 6종 — GPU Utilization, GPU Memory Utilization, "
                    + "VRAM Used/Total(GB), GPU Power Usage(W), GPU Temperature(°C), Fan Speed(%) 를 조회합니다. (구현 전 — 501)")
    @GetMapping("/instances/{instanceId}/summary")
    public ApiResponse<GpuDeviceSummaryResponse> getInstanceSummary(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String nodeId,
            @PathVariable String instanceId) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getInstanceSummary(roleName, nsId, infraId, nodeId, instanceId));
    }

    @Operation(summary = "GPU 인스턴스 할당 정보",
            description = "가상머신 할당 정보 표 — 인스턴스/가상머신/타입/매핑된 물리 GPU/"
                    + "VRAM 할당·사용/라이선스 상태를 조회합니다. (구현 전 — 501)")
    @GetMapping("/instances/{instanceId}/allocation")
    public ApiResponse<VmGpuAllocationResponse> getInstanceAllocation(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String nodeId,
            @PathVariable String instanceId) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getInstanceAllocation(roleName, nsId, infraId, nodeId, instanceId));
    }

    @Operation(summary = "GPU 인스턴스 기본 정보",
            description = "GPU 기본 정보 표 — 모델/UUID/CUDA 버전/NVIDIA Driver 버전/수집 상태/마지막 수집 시각을 조회합니다. (구현 전 — 501)")
    @GetMapping("/instances/{instanceId}/info")
    public ApiResponse<VmGpuInstanceInfoResponse> getInstanceInfo(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String nodeId,
            @PathVariable String instanceId) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getInstanceInfo(roleName, nsId, infraId, nodeId, instanceId));
    }

    @Operation(summary = "GPU 인스턴스 모니터링 차트",
            description = "개별 인스턴스 모니터링 탭 — 해당 인스턴스의 지표별 시계열. interval 은 조회 기간(지금 기준)입니다. "
                    + "화면 설계 확정 전으로 지표 세트가 변경될 수 있습니다. (구현 전 — 501)")
    @GetMapping("/instances/{instanceId}/metrics/trend")
    public ApiResponse<GpuMetricTrendResponse> getInstanceMetricTrend(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String nodeId,
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "GPU_UTILIZATION") GpuMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getInstanceMetricTrend(
                roleName, nsId, infraId, nodeId, instanceId, metric, interval));
    }
}

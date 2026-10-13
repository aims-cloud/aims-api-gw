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
 * VM 은 nsId·infraId·vmId 쿼리 파라미터로 식별한다(다른 VM 패널과 동일). 데이터는 VM 안 dcgm_exporter 지표를
 * tb_* 라벨로 읽고, 인스턴스는 GPU UUID 로 식별한다. 할당 방식은 MIG 만, 물리 GPU 매핑·라이선스는 할당 원장 전까지 null 이다.
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
                    + "평균/최대 GPU Memory Utilization(VRAM 사용률), 최근 알람 수를 조회합니다. "
                    + "총 GPU 수는 최근 24시간 안에 수집된 GPU 수, 사용률은 지금 수집 중인 GPU 기준입니다. "
                    + "최근 알람 수는 GPU 단위 알람 기록 전까지 null 입니다. VM 안 dcgm_exporter 지표를 tb_ns/tb_infra/tb_node 라벨로 읽습니다. 지금 수집되지 않는 값은 null 입니다.")
    @GetMapping("/summary")
    public ApiResponse<VmGpuGroupSummaryResponse> getGroupSummary(@RequestParam(required = false) String roleName,
                                                                  @RequestParam String nsId,
                                                                  @RequestParam String infraId,
                                                                  @RequestParam String vmId) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getGroupSummary(roleName, nsId, infraId, vmId));
    }

    @Operation(summary = "GPU 인스턴스 목록",
            description = "그룹 개요 탭 목록 — 인스턴스(instanceId=GPU UUID, instanceName=트리 표시 이름)/타입/"
                    + "GPU util(현재/최근 1시간 평균)/GPU Mem util(VRAM 사용률, 현재/평균)/VRAM(사용/할당)/수집 상태/최근 알람 수. "
                    + "타입은 VM 안에서 MIG 만 판별되어 MIG 가 아니면 null 입니다. "
                    + "컬럼 정렬과 페이징을 지원합니다. page 는 1부터, sort 는 '{필드},{asc|desc}' 형식이며 "
                    + "instanceName 은 인덱스 숫자순, 값 없음은 방향과 무관하게 마지막입니다. VM 안 dcgm_exporter 지표를 tb_ns/tb_infra/tb_node 라벨로 읽습니다. 지금 수집되지 않는 값은 null 입니다.")
    @GetMapping("/instances")
    public ApiResponse<PageResponse<VmGpuInstanceListItemResponse>> getInstances(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "instanceName,asc") String sort) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getInstances(roleName, nsId, infraId, vmId, page, size, sort));
    }

    @Operation(summary = "VM GPU 그룹 모니터링 차트",
            description = "그룹 모니터링 탭 — metric 별 인스턴스 분해 시계열. interval 은 조회 기간(지금 기준)입니다. "
                    + "화면 설계 확정 전으로 지표 세트가 변경될 수 있습니다. 시리즈 이름은 트리와 같은 'GPU {인덱스}' 입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<GpuMetricTrendResponse> getGroupMetricTrend(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @RequestParam(defaultValue = "GPU_UTILIZATION") GpuMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getGroupMetricTrend(roleName, nsId, infraId, vmId, metric, interval));
    }

    // ── 개별 GPU 인스턴스 (GPU 장치 선택) ───────────────────────

    @Operation(summary = "GPU 인스턴스 개요 KPI",
            description = "개별 인스턴스 개요 탭 카드 6종 — GPU Utilization, GPU Memory Utilization, "
                    + "VRAM Used/Total(GB), GPU Power Usage(W), GPU Temperature(°C), Fan Speed(%) 를 조회합니다. "
                    + "instanceId 는 GPU UUID 이며 최근 24시간 안에 수집된 적 없으면 404 입니다. "
                    + "Fan Speed 는 수집 설정에 DCGM_FI_DEV_FAN_SPEED 가 있어야 채워집니다. VM 안 dcgm_exporter 지표를 tb_ns/tb_infra/tb_node 라벨로 읽습니다. 지금 수집되지 않는 값은 null 입니다.")
    @GetMapping("/instances/{instanceId}/summary")
    public ApiResponse<GpuDeviceSummaryResponse> getInstanceSummary(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @PathVariable String instanceId) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getInstanceSummary(roleName, nsId, infraId, vmId, instanceId));
    }

    @Operation(summary = "GPU 인스턴스 할당 정보",
            description = "가상머신 할당 정보 표 — 인스턴스/가상머신/타입/매핑된 물리 GPU/"
                    + "VRAM 할당·사용/라이선스 상태를 조회합니다. VM 안에서 알 수 있는 값만 채웁니다 — 타입은 MIG 만 판별(아니면 null), "
                    + "매핑된 물리 GPU·라이선스 상태는 하이퍼바이저 쪽 정보라 GPU 할당 원장 확정 전까지 null 입니다.")
    @GetMapping("/instances/{instanceId}/allocation")
    public ApiResponse<VmGpuAllocationResponse> getInstanceAllocation(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @PathVariable String instanceId) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getInstanceAllocation(roleName, nsId, infraId, vmId, instanceId));
    }

    @Operation(summary = "GPU 인스턴스 기본 정보",
            description = "GPU 기본 정보 표 — 모델/UUID/CUDA 버전/NVIDIA Driver 버전/수집 상태/마지막 수집 시각을 조회합니다. "
                    + "드라이버 버전은 DCGM_FI_DRIVER_VERSION 라벨, CUDA 버전은 기본 수집 항목에 없어 null, 수집 상태는 COLLECTING|UNAVAILABLE 입니다.")
    @GetMapping("/instances/{instanceId}/info")
    public ApiResponse<VmGpuInstanceInfoResponse> getInstanceInfo(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @PathVariable String instanceId) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getInstanceInfo(roleName, nsId, infraId, vmId, instanceId));
    }

    @Operation(summary = "GPU 인스턴스 모니터링 차트",
            description = "개별 인스턴스 모니터링 탭 — 해당 인스턴스의 지표별 시계열. interval 은 조회 기간(지금 기준)입니다. "
                    + "화면 설계 확정 전으로 지표 세트가 변경될 수 있습니다.")
    @GetMapping("/instances/{instanceId}/metrics/trend")
    public ApiResponse<GpuMetricTrendResponse> getInstanceMetricTrend(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @PathVariable String instanceId,
            @RequestParam(defaultValue = "GPU_UTILIZATION") GpuMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerVmGpuService.getInstanceMetricTrend(
                roleName, nsId, infraId, vmId, instanceId, metric, interval));
    }
}

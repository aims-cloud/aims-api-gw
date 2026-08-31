package com.innogrid.aims.monitoring.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuClocksEventsResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuCollectStatusResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceCollectStatusResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuDeviceSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuHealthSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuPcieReplayResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.gpu.GpuXidErrorsResponse;
import com.innogrid.aims.monitoring.service.ResourceExplorerNodeGpuService;
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
 * 자원 탐색기 > 노드 GPU 카테고리 패널 API.
 *
 * 그룹(트리에서 GPU 카테고리 선택)과 개별 GPU(하위 GPU 장치 선택)의
 * 개요·모니터링 탭을 하나의 컨트롤러에서 제공한다.
 *   그룹 개요    — KPI 카드 4종, 상태 요약(이상 감지), GPU 목록 테이블
 *   개별 GPU 개요 — KPI 카드 6종, 상태 요약, GPU 정보 표
 *   상태 요약 카드 상세 — 수집 상태(그룹/개별), XID Errors, PCIe Replay Counter, Clocks Event Reasons
 *   모니터링     — 지표별 시계열 (화면 설계 확정 전, 잠정 지표 세트)
 * 노드 상세 패널은 ResourceExplorerNodeController 가 담당한다.
 */
@Tag(name = "자원 탐색기 (노드 GPU)",
        description = "노드 GPU 카테고리 — 그룹/개별 GPU KPI, 상태 요약, GPU 목록·정보, 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node/{nodeId}/gpu")
public class ResourceExplorerNodeGpuController {

    private final ResourceExplorerNodeGpuService resourceExplorerNodeGpuService;

    public ResourceExplorerNodeGpuController(ResourceExplorerNodeGpuService resourceExplorerNodeGpuService) {
        this.resourceExplorerNodeGpuService = resourceExplorerNodeGpuService;
    }

    // ── 그룹 (GPU 카테고리 선택) ────────────────────────────────

    @Operation(summary = "GPU 그룹 개요 KPI",
            description = "그룹 개요 탭 카드 4종 — 총 GPU 수, VRAM Utilization(사용/전체 GB 포함), "
                    + "GPU Power Usage(합산 W, Peak 포함), 최대 GPU Temperature 를 조회합니다.")
    @GetMapping("/summary")
    public ApiResponse<GpuGroupSummaryResponse> getGroupSummary(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeGpuService.getGroupSummary(nodeId));
    }

    @Operation(summary = "GPU 그룹 상태 요약",
            description = "그룹 개요 탭 상태 요약 — 이상 감지 배너(단계·대상 GPU 수·문구)와 "
                    + "수집 상태 / ECC DBE Errors / PCIe Replay Counter / Clocks Event Reasons 카드를 조회합니다.")
    @GetMapping("/health")
    public ApiResponse<GpuHealthSummaryResponse> getGroupHealth(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeGpuService.getGroupHealth(nodeId));
    }

    @Operation(summary = "GPU 목록",
            description = "그룹 개요 탭 목록 — GPU/모델/GPU Util(현재/평균)/VRAM Util(현재/평균)/VRAM(사용/전체)/"
                    + "온도/전력/최근 알람 수. 컬럼 정렬과 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/devices")
    public ApiResponse<PageResponse<GpuDeviceListItemResponse>> getDevices(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "gpuId,asc") String sort) {
        return ApiResponse.ok(resourceExplorerNodeGpuService.getDevices(nodeId, page, size, sort));
    }

    @Operation(summary = "GPU 그룹 모니터링 차트",
            description = "그룹 모니터링 탭 — metric(GPU_UTILIZATION|VRAM_UTILIZATION|VRAM_USED|GPU_POWER|"
                    + "GPU_TEMPERATURE|FAN_SPEED)별 GPU 별 분해 시계열. "
                    + "화면 설계 확정 전으로 지표 세트가 변경될 수 있습니다. from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<GpuMetricTrendResponse> getGroupMetricTrend(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "GPU_UTILIZATION") GpuMetric metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "300s") String step) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerNodeGpuService.getGroupMetricTrend(
                nodeId, metric, fromInstant, toInstant, step));
    }

    // ── 개별 GPU (GPU 장치 선택) ────────────────────────────────

    @Operation(summary = "GPU 장치 개요 KPI",
            description = "개별 GPU 개요 탭 카드 6종 — GPU Utilization, VRAM Utilization, VRAM Used/Total(GB), "
                    + "GPU Power Usage(W), GPU Temperature(°C), Fan Speed(%) 를 조회합니다.")
    @GetMapping("/devices/{gpuId}/summary")
    public ApiResponse<GpuDeviceSummaryResponse> getDeviceSummary(
            @PathVariable String nodeId,
            @PathVariable String gpuId) {
        return ApiResponse.ok(resourceExplorerNodeGpuService.getDeviceSummary(nodeId, gpuId));
    }

    @Operation(summary = "GPU 장치 상태 요약",
            description = "개별 GPU 상태 요약 — 그룹과 동일 구조를 해당 GPU 1장 기준으로 조회합니다.")
    @GetMapping("/devices/{gpuId}/health")
    public ApiResponse<GpuHealthSummaryResponse> getDeviceHealth(
            @PathVariable String nodeId,
            @PathVariable String gpuId) {
        return ApiResponse.ok(resourceExplorerNodeGpuService.getDeviceHealth(nodeId, gpuId));
    }

    @Operation(summary = "GPU 장치 정보",
            description = "개별 GPU 개요 탭 정보 표 — GPU 모델/UUID/PCI Bus ID/CUDA 버전/NVIDIA 드라이버 버전/"
                    + "NVML 버전/VBIOS 버전/P-State/수집 상태/마지막 수집 시각을 조회합니다.")
    @GetMapping("/devices/{gpuId}/info")
    public ApiResponse<GpuDeviceInfoResponse> getDeviceInfo(
            @PathVariable String nodeId,
            @PathVariable String gpuId) {
        return ApiResponse.ok(resourceExplorerNodeGpuService.getDeviceInfo(nodeId, gpuId));
    }

    @Operation(summary = "GPU 장치 모니터링 차트",
            description = "개별 GPU 모니터링 탭 — 해당 GPU 의 지표별 시계열. "
                    + "화면 설계 확정 전으로 지표 세트가 변경될 수 있습니다. from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/devices/{gpuId}/metrics/trend")
    public ApiResponse<GpuMetricTrendResponse> getDeviceMetricTrend(
            @PathVariable String nodeId,
            @PathVariable String gpuId,
            @RequestParam(defaultValue = "GPU_UTILIZATION") GpuMetric metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "300s") String step) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerNodeGpuService.getDeviceMetricTrend(
                nodeId, gpuId, metric, fromInstant, toInstant, step));
    }

    // ── 상태 요약 카드 상세 패널 ────────────────────────────────

    @Operation(summary = "수집 상태 상세 (그룹)",
            description = "상태 요약의 수집 상태 카드 상세 — 수집 대상/정상/오류 GPU 수, 최근 수집 시각, 수집 주기와 "
                    + "GPU별 수집 상태(HEALTHY|FAILED|NO_DATA)·마지막 수집·수집 지표 수를 조회합니다.")
    @GetMapping("/health/collect-status")
    public ApiResponse<GpuCollectStatusResponse> getCollectStatus(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeGpuService.getCollectStatus(nodeId));
    }

    @Operation(summary = "수집 상태 상세 (개별 GPU)",
            description = "개별 GPU 의 수집 상태 상세 — 요약 표와 지표별 수집 성공 여부 체크리스트를 조회합니다.")
    @GetMapping("/devices/{gpuId}/health/collect-status")
    public ApiResponse<GpuDeviceCollectStatusResponse> getDeviceCollectStatus(
            @PathVariable String nodeId,
            @PathVariable String gpuId) {
        return ApiResponse.ok(resourceExplorerNodeGpuService.getDeviceCollectStatus(nodeId, gpuId));
    }

    @Operation(summary = "XID Errors 상세",
            description = "ECC DBE Errors 카드의 상세 — 총 발생 건수·최근 발생과 XID 오류별 "
                    + "발생 컨텍스트(재발 빈도 24h, 발생 시점 워크로드), 해석 후보(원인 태그·조치 안내)를 조회합니다. "
                    + "gpuId 미지정 시 그룹 전체입니다.")
    @GetMapping("/health/xid-errors")
    public ApiResponse<GpuXidErrorsResponse> getXidErrors(
            @PathVariable String nodeId,
            @RequestParam(required = false) String gpuId) {
        return ApiResponse.ok(resourceExplorerNodeGpuService.getXidErrors(nodeId, gpuId));
    }

    @Operation(summary = "PCIe Replay Counter 상세",
            description = "GPU별 누적값/최근 1시간/최근 24시간 증가와 최근 24시간 증가 추이(회/h)를 한 응답으로 조회합니다. "
                    + "gpuId 미지정 시 그룹 전체입니다.")
    @GetMapping("/health/pcie-replay")
    public ApiResponse<GpuPcieReplayResponse> getPcieReplay(
            @PathVariable String nodeId,
            @RequestParam(required = false) String gpuId) {
        return ApiResponse.ok(resourceExplorerNodeGpuService.getPcieReplay(nodeId, gpuId));
    }

    @Operation(summary = "Clocks Event Reasons 상세",
            description = "GPU별 활성 이벤트(DISPLAY_CLK|HW_PWR_BRAKE|HW_THERMAL|SW_THERMAL|SYNC_BOOST|"
                    + "HW_SLOWDOWN|SW_POWER_CAP|APP_CLOCKS|GPU_IDLE)와 안내 텍스트를 조회합니다. "
                    + "활성 이벤트 매트릭스는 프론트가 activeReasons 로 렌더링합니다. gpuId 미지정 시 그룹 전체입니다.")
    @GetMapping("/health/clocks-events")
    public ApiResponse<GpuClocksEventsResponse> getClocksEvents(
            @PathVariable String nodeId,
            @RequestParam(required = false) String gpuId) {
        return ApiResponse.ok(resourceExplorerNodeGpuService.getClocksEvents(nodeId, gpuId));
    }

    /** from 기본값: 오늘 00:00:00 (서버 타임존) */
    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}

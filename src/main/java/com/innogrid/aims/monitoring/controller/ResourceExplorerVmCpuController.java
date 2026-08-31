package com.innogrid.aims.monitoring.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.cpu.VmCpuMetricTrendResponse;
import com.innogrid.aims.monitoring.service.ResourceExplorerVmCpuService;
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
 * 자원 탐색기 > VM CPU 카테고리 패널 API.
 *
 * 트리에서 VM 하위 CPU 항목을 선택했을 때 오른쪽 패널의 탭별 데이터를 제공한다.
 *   개요 탭     — KPI 카드 4종(Used/I/O Wait/System/Steal), CPU 목록 테이블
 *   모니터링 탭 — CPU mode 별 시계열 차트 9종 (노드 CPU 와 지표 세트가 다름)
 * 다른 VM 카테고리(Memory/Disk/…)도 동일한 경로 패턴
 * (/vm/{vmId}/{category}/summary|devices|metrics/trend)을 따른다.
 * VM 상세 패널은 ResourceExplorerVmController, 노드 CPU 는 ResourceExplorerNodeCpuController 가 담당한다.
 */
@Tag(name = "자원 탐색기 (VM CPU)", description = "VM CPU 카테고리 — KPI 카드, CPU 목록, 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/vm/{vmId}/cpu")
public class ResourceExplorerVmCpuController {

    private final ResourceExplorerVmCpuService resourceExplorerVmCpuService;

    public ResourceExplorerVmCpuController(ResourceExplorerVmCpuService resourceExplorerVmCpuService) {
        this.resourceExplorerVmCpuService = resourceExplorerVmCpuService;
    }

    @Operation(summary = "VM CPU 개요 KPI",
            description = "개요 탭 카드 4종 — CPU Used, CPU I/O Wait, CPU System, CPU Steal (%) 을 조회합니다. "
                    + "미수집 지표는 null 로 내려갑니다.")
    @GetMapping("/summary")
    public ApiResponse<CpuSummaryResponse> getCpuSummary(@PathVariable String vmId) {
        return ApiResponse.ok(resourceExplorerVmCpuService.getCpuSummary(vmId));
    }

    @Operation(summary = "VM CPU 목록",
            description = "개요 탭 CPU 목록 — 소켓/벤더/모델/코어 수/스레드 수/수집 상태. "
                    + "컬럼 정렬과 페이징을 지원합니다. page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/devices")
    public ApiResponse<PageResponse<VmCpuListItemResponse>> getCpuDevices(
            @PathVariable String vmId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "socket,asc") String sort) {
        return ApiResponse.ok(resourceExplorerVmCpuService.getCpuDevices(vmId, page, size, sort));
    }

    @Operation(summary = "VM CPU 모니터링 차트",
            description = "모니터링 탭 — metric(CPU_USED|CPU_IDLE|CPU_USER|CPU_SYSTEM|CPU_IO_WAIT|"
                    + "CPU_NICE|CPU_IRQ|CPU_SOFT_IRQ|CPU_STEAL)별 시계열을 조회합니다. "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<VmCpuMetricTrendResponse> getCpuMetricTrend(
            @PathVariable String vmId,
            @RequestParam(defaultValue = "CPU_USED") VmCpuMetric metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "300s") String step) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerVmCpuService.getCpuMetricTrend(
                vmId, metric, fromInstant, toInstant, step));
    }

    /** from 기본값: 오늘 00:00:00 (서버 타임존) */
    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}

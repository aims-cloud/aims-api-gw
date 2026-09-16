package com.innogrid.aims.monitoring.controller.resourceexplorer.node;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuDeviceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.cpu.CpuSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeCpuService;
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
 * 자원 탐색기 > 노드 CPU 카테고리 패널 API.
 *
 * 트리에서 노드 하위 CPU 항목을 선택했을 때 오른쪽 패널의 탭별 데이터를 제공한다.
 *   개요 탭     — KPI 카드 4종(Used/I/O Wait/System/Steal), CPU 목록 테이블
 *   모니터링 탭 — CPU mode 별 시계열 차트 (기간은 interval, 자동 갱신은 프론트 재호출)
 * 다른 카테고리(Memory/File system/Network/GPU)도 동일한 경로 패턴
 * (/node/{nodeId}/{category}/summary|devices|metrics/trend)을 따른다.
 * 노드 상세 패널은 ResourceExplorerNodeController 가 담당한다. 스코프 밖 노드는 404 다.
 */
@Tag(name = "관측 > 자원 탐색기 (노드 CPU)", description = "노드 CPU 카테고리 — KPI 카드, CPU 목록, 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node/{nodeId}/cpu")
public class ResourceExplorerNodeCpuController {

    private final ResourceExplorerNodeCpuService resourceExplorerNodeCpuService;

    public ResourceExplorerNodeCpuController(ResourceExplorerNodeCpuService resourceExplorerNodeCpuService) {
        this.resourceExplorerNodeCpuService = resourceExplorerNodeCpuService;
    }

    @Operation(summary = "CPU 개요 KPI",
            description = "개요 탭 카드 4종 — CPU Used, CPU I/O Wait, CPU System, CPU Steal (%, 최근 5분 rate) 을 조회합니다. "
                    + "노드의 node_exporter 를 instance 라벨로 조회하며 미수집 지표는 null 로 내려갑니다.")
    @GetMapping("/summary")
    public ApiResponse<CpuSummaryResponse> getCpuSummary(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeCpuService.getCpuSummary(nodeId));
    }

    @Operation(summary = "CPU 목록",
            description = "개요 탭 CPU 목록 — CPU ID/소켓/벤더/모델/코어 수/스레드 수/기본 클럭/수집 상태/마지막 수집. "
                    + "node_cpu_info(collector.cpu.info) 가 있으면 소켓별로, 없으면 논리 CPU 수만으로 한 행(벤더·모델·클럭 null)을 만듭니다. "
                    + "컬럼 정렬(cpuId|socket|vendor|model|coreCount|threadCount|baseClockGhz)과 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/devices")
    public ApiResponse<PageResponse<CpuDeviceListItemResponse>> getCpuDevices(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "cpuId,asc") String sort) {
        return ApiResponse.ok(resourceExplorerNodeCpuService.getCpuDevices(nodeId, page, size, sort));
    }

    @Operation(summary = "CPU 모니터링 차트",
            description = "모니터링 탭 — metric(CPU_USED|CPU_IDLE|CPU_USER|CPU_SYSTEM|CPU_IO_WAIT|"
                    + "CPU_NICE|CPU_IRQ|CPU_SOFT_IRQ|CPU_STEAL)별 시계열(%)을 조회합니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 조회 기간(지금 기준)이며 "
                    + "버킷 간격은 기간별 기본값(30s/5m/15m/1h/1d/3d)입니다. 미수집이면 빈 시리즈입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<CpuMetricTrendResponse> getCpuMetricTrend(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "CPU_USED") CpuMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerNodeCpuService.getCpuMetricTrend(nodeId, metric, interval));
    }
}

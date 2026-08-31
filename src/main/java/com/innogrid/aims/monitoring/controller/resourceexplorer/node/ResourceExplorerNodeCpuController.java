package com.innogrid.aims.monitoring.controller.resourceexplorer.node;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
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
 *   모니터링 탭 — 지표별 시계열 차트 (주기·기간·자동 갱신은 from/to 재계산으로 프론트가 처리)
 * 다른 카테고리(Memory/File system/Network/GPU)도 동일한 경로 패턴
 * (/node/{nodeId}/{category}/summary|devices|metrics/trend)을 따른다.
 * 노드 상세 패널은 ResourceExplorerNodeController 가 담당한다.
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
            description = "개요 탭 카드 4종 — CPU Used, CPU I/O Wait, CPU System, CPU Steal (%) 을 조회합니다.")
    @GetMapping("/summary")
    public ApiResponse<CpuSummaryResponse> getCpuSummary(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeCpuService.getCpuSummary(nodeId));
    }

    @Operation(summary = "CPU 목록",
            description = "개요 탭 CPU 목록 — CPU ID/소켓/벤더/모델/코어 수/스레드 수/기본 클럭/수집 상태/마지막 수집. "
                    + "컬럼 정렬과 페이징을 지원합니다. page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/devices")
    public ApiResponse<PageResponse<CpuDeviceListItemResponse>> getCpuDevices(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "cpuId,asc") String sort) {
        return ApiResponse.ok(resourceExplorerNodeCpuService.getCpuDevices(nodeId, page, size, sort));
    }

    @Operation(summary = "CPU 모니터링 차트",
            description = "모니터링 탭 — metric(CPU_USAGE|CPU_UTILIZATION|CPU_USAGE_MHZ|CPU_USAGE_TIME|"
                    + "CPU_IDLE_TIME|CPU_WAIT_TIME|CPU_READINESS)별 시계열을 조회합니다. "
                    + "CPU_READINESS 는 하이퍼바이저 전용 지표로 물리 노드에서는 미지원입니다. "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<CpuMetricTrendResponse> getCpuMetricTrend(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "CPU_USAGE") CpuMetric metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "300s") String step) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerNodeCpuService.getCpuMetricTrend(
                nodeId, metric, fromInstant, toInstant, step));
    }

    /** from 기본값: 오늘 00:00:00 (서버 타임존) */
    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}

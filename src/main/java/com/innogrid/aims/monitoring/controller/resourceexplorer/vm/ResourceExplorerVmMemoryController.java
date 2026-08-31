package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.memory.VmMemoryMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.memory.VmMemoryMetricTrendResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerVmMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > VM Memory 카테고리 패널 API.
 *
 * 트리에서 VM 하위 Memory 항목을 선택했을 때 오른쪽 모니터링 탭 차트 9종
 * (Used/Used Percent/Free/Available/Available Percent/Total/Cached/Buffered/Shared)을 제공한다.
 * 노드 Memory(5종)와 달리 Free(MemFree)와 Available(MemAvailable)을 구분한다.
 * 개요 탭은 화면 설계 미확정 — 추가 시 CPU 패턴대로 확장한다.
 * VM 상세 패널은 ResourceExplorerVmController 가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (VM Memory)", description = "VM Memory 카테고리 — 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/vm/{vmId}/memory")
public class ResourceExplorerVmMemoryController {

    private final ResourceExplorerVmMemoryService resourceExplorerVmMemoryService;

    public ResourceExplorerVmMemoryController(ResourceExplorerVmMemoryService resourceExplorerVmMemoryService) {
        this.resourceExplorerVmMemoryService = resourceExplorerVmMemoryService;
    }

    @Operation(summary = "VM Memory 모니터링 차트",
            description = "모니터링 탭 — metric(MEMORY_USED|MEMORY_USED_PERCENT|MEMORY_FREE|MEMORY_AVAILABLE|"
                    + "MEMORY_AVAILABLE_PERCENT|MEMORY_TOTAL|MEMORY_CACHED|MEMORY_BUFFERED|MEMORY_SHARED)별 "
                    + "시계열을 조회합니다. Free 는 MemFree(순수 미사용), Available 은 MemAvailable(캐시 회수 가능분 포함) "
                    + "기준입니다. from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<VmMemoryMetricTrendResponse> getMemoryMetricTrend(
            @PathVariable String vmId,
            @RequestParam(defaultValue = "MEMORY_USED") VmMemoryMetric metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "300s") String step) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerVmMemoryService.getMemoryMetricTrend(
                vmId, metric, fromInstant, toInstant, step));
    }

    /** from 기본값: 오늘 00:00:00 (서버 타임존) */
    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}

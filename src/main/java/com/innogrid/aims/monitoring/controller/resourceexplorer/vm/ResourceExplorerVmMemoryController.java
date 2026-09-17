package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.memory.VmMemoryMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.memory.VmMemoryMetricTrendResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerVmMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > VM Memory 카테고리 패널 API.
 *
 * 트리에서 VM 하위 Memory 항목을 선택했을 때 모니터링 탭 차트 데이터를 제공한다.
 * 개요 탭은 화면 설계 미확정 — 추가 시 CPU 패턴대로 확장한다.
 * VM 은 nsId·infraId·vmId 쿼리 파라미터로 식별한다(VM 상세·CPU 패널과 동일).
 * VM 상세 패널은 ResourceExplorerVmController 가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (VM Memory)", description = "VM Memory 카테고리 — 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/vm/memory")
public class ResourceExplorerVmMemoryController {

    private final ResourceExplorerVmMemoryService resourceExplorerVmMemoryService;

    public ResourceExplorerVmMemoryController(ResourceExplorerVmMemoryService resourceExplorerVmMemoryService) {
        this.resourceExplorerVmMemoryService = resourceExplorerVmMemoryService;
    }

    @Operation(summary = "VM Memory 모니터링 차트",
            description = "모니터링 탭 — metric(MEMORY_USED|MEMORY_USED_PERCENT|MEMORY_FREE|MEMORY_AVAILABLE|"
                    + "MEMORY_AVAILABLE_PERCENT|MEMORY_TOTAL|MEMORY_CACHED|MEMORY_BUFFERED|MEMORY_SHARED)별 "
                    + "시계열(GB 또는 %)을 조회합니다. Free 는 MemFree(순수 미사용), Available 은 MemAvailable(캐시 회수 가능분 포함) "
                    + "기준입니다. interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 조회 기간(지금 기준)이며 "
                    + "버킷 간격은 기간별 기본값입니다. 미수집이면 빈 시리즈입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<VmMemoryMetricTrendResponse> getMemoryMetricTrend(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @RequestParam(defaultValue = "MEMORY_USED") VmMemoryMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerVmMemoryService.getMemoryMetricTrend(
                roleName, nsId, infraId, vmId, metric, interval));
    }
}

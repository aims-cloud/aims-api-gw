package com.innogrid.aims.monitoring.controller.resourceexplorer.node;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.memory.MemoryMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.memory.MemoryMetricTrendResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > 노드 Memory 카테고리 패널 API.
 *
 * 트리에서 노드 하위 Memory 항목을 선택했을 때 오른쪽 모니터링 탭 차트 5종
 * (Used/Used Percent/Free/Free Percent/Total)을 제공한다.
 * 개요 탭은 화면 설계 미확정 — 추가 시 CPU 패턴대로 summary/devices 를 확장한다.
 * 경로 패턴은 다른 카테고리와 동일하게 /node/{nodeId}/{category}/metrics/trend 를 따른다.
 * 노드 상세 패널은 ResourceExplorerNodeController 가 담당한다.
 */
@Tag(name = "자원 탐색기 (노드 Memory)", description = "노드 Memory 카테고리 — 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node/{nodeId}/memory")
public class ResourceExplorerNodeMemoryController {

    private final ResourceExplorerNodeMemoryService resourceExplorerNodeMemoryService;

    public ResourceExplorerNodeMemoryController(ResourceExplorerNodeMemoryService resourceExplorerNodeMemoryService) {
        this.resourceExplorerNodeMemoryService = resourceExplorerNodeMemoryService;
    }

    @Operation(summary = "Memory 모니터링 차트",
            description = "모니터링 탭 — metric(MEMORY_USED|MEMORY_USED_PERCENT|MEMORY_FREE|"
                    + "MEMORY_FREE_PERCENT|MEMORY_TOTAL)별 시계열을 조회합니다. "
                    + "Free 는 MemAvailable 기준(Used + Free = Total 성립)입니다. "
                    + "from/to 미지정 시 오늘 00시~현재입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<MemoryMetricTrendResponse> getMemoryMetricTrend(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "MEMORY_USED") MemoryMetric metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "300s") String step) {
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : todayStart();
        return ApiResponse.ok(resourceExplorerNodeMemoryService.getMemoryMetricTrend(
                nodeId, metric, fromInstant, toInstant, step));
    }

    /** from 기본값: 오늘 00:00:00 (서버 타임존) */
    private static Instant todayStart() {
        return LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}

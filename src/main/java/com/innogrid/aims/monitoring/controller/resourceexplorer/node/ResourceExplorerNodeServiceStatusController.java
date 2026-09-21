package com.innogrid.aims.monitoring.controller.resourceexplorer.node;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceStatusSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.servicestatus.ServiceUnitState;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeServiceStatusService;
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
 * 자원 탐색기 > 노드 Service Status 카테고리 패널 API.
 *
 * 트리에서 노드 하위 Service Status 항목을 선택했을 때 오른쪽 패널의 탭별 데이터를 제공한다.
 *   개요 탭     — KPI 카드(전체·active·inactive·failed·activating·deactivating 유닛 수), systemd 유닛 목록
 *   모니터링 탭 — 상태별 유닛 수 시계열 3종
 * node_exporter 에 --collector.systemd 가 켜진 노드에서만 데이터가 있다(docs/06 §5.4). 스코프 밖 노드는 404 다.
 */
@Tag(name = "관측 > 자원 탐색기 (노드 Service Status)", description = "노드 Service Status 카테고리 — KPI 카드, systemd 유닛 목록, 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node/{nodeId}/service-status")
public class ResourceExplorerNodeServiceStatusController {

    private final ResourceExplorerNodeServiceStatusService resourceExplorerNodeServiceStatusService;

    public ResourceExplorerNodeServiceStatusController(ResourceExplorerNodeServiceStatusService resourceExplorerNodeServiceStatusService) {
        this.resourceExplorerNodeServiceStatusService = resourceExplorerNodeServiceStatusService;
    }

    @Operation(summary = "Service Status 개요 KPI",
            description = "개요 탭 카드 — systemd 유닛 전체 수와 active·inactive·failed·activating·deactivating 수. "
                    + "node_exporter 에 --collector.systemd 가 없는 노드는 표본이 없어 전부 null 입니다.")
    @GetMapping("/summary")
    public ApiResponse<ServiceStatusSummaryResponse> getServiceStatusSummary(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeServiceStatusService.getServiceStatusSummary(nodeId));
    }

    @Operation(summary = "systemd 유닛 목록",
            description = "개요 탭 유닛 목록 — 유닛명/유형/상태. state(ACTIVE|INACTIVE|FAILED|ACTIVATING|DEACTIVATING) 필터와 "
                    + "keyword(유닛명 부분 일치, 대소문자 무시), 컬럼 정렬(name|type|state), 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/units")
    public ApiResponse<PageResponse<ServiceUnitListItemResponse>> getServiceUnits(
            @PathVariable String nodeId,
            @RequestParam(required = false) ServiceUnitState state,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerNodeServiceStatusService.getServiceUnits(nodeId, state, keyword, page, size, sort));
    }

    @Operation(summary = "Service Status 모니터링 차트",
            description = "모니터링 탭 — metric(ACTIVE_UNITS|FAILED_UNITS|INACTIVE_UNITS)별 상태 유닛 수 시계열을 조회합니다. "
                    + "해당 상태 유닛이 없으면 0, systemd 콜렉터가 없으면 빈 시리즈입니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 조회 기간(지금 기준)이며 "
                    + "버킷 간격은 기간별 기본값(30s/5m/15m/1h/1d/3d)입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<ServiceStatusMetricTrendResponse> getServiceStatusMetricTrend(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "ACTIVE_UNITS") ServiceStatusMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerNodeServiceStatusService.getServiceStatusMetricTrend(nodeId, metric, interval));
    }
}

package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.swap.SwapSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerVmSwapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > VM Swap 카테고리 패널 API.
 *
 * 트리에서 VM 하위 Swap 항목을 선택했을 때 오른쪽 패널의 탭별 데이터를 제공한다.
 *   개요 탭     — KPI 카드 6종(Total/Used/Free, 사용률, 스왑 인·아웃)
 *   모니터링 탭 — 지표 6종 시계열 차트
 * VM 은 다른 VM 패널과 같이 nsId·infraId·vmId 쿼리 파라미터로 식별한다(트리 VIRTUAL_MACHINE 노드의 같은 이름 필드).
 * 응답 형식은 노드 Swap 패널(ResourceExplorerNodeSwapController)과 같다. 스코프 밖·없는 VM 은 404 다.
 */
@Tag(name = "관측 > 자원 탐색기 (VM Swap)", description = "VM Swap 카테고리 — KPI 카드, 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/vm/swap")
public class ResourceExplorerVmSwapController {

    private final ResourceExplorerVmSwapService resourceExplorerVmSwapService;

    public ResourceExplorerVmSwapController(ResourceExplorerVmSwapService resourceExplorerVmSwapService) {
        this.resourceExplorerVmSwapService = resourceExplorerVmSwapService;
    }

    @Operation(summary = "VM Swap 개요 KPI",
            description = "개요 탭 카드 6종 — Total(GB), Used(GB), Free(GB), Used(%), Swap In(pages/s), Swap Out(pages/s). "
                    + "VM 안 node_exporter 를 tb_ns/tb_infra/tb_node 라벨로 조회하며 인·아웃은 최근 5분 rate 입니다. "
                    + "스왑이 없는 VM 은 용량 0 에 사용률 null, 미수집은 전부 null 입니다.")
    @GetMapping("/summary")
    public ApiResponse<SwapSummaryResponse> getSwapSummary(@RequestParam(required = false) String roleName,
                                                           @RequestParam String nsId,
                                                           @RequestParam String infraId,
                                                           @RequestParam String vmId) {
        return ApiResponse.ok(resourceExplorerVmSwapService.getSwapSummary(roleName, nsId, infraId, vmId));
    }

    @Operation(summary = "VM Swap 모니터링 차트",
            description = "모니터링 탭 — metric(SWAP_USED|SWAP_USED_PERCENT|SWAP_FREE|SWAP_TOTAL|SWAP_IN|SWAP_OUT)별 "
                    + "시계열(GB, %, pages/s)을 조회합니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 조회 기간(지금 기준)이며 "
                    + "버킷 간격은 기간별 기본값(30s/5m/15m/1h/1d/3d)입니다. 미수집이거나 스왑이 없어 정의되지 않는 사용률은 빈 시리즈입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<SwapMetricTrendResponse> getSwapMetricTrend(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @RequestParam(defaultValue = "SWAP_USED") SwapMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerVmSwapService.getSwapMetricTrend(roleName, nsId, infraId, vmId, metric, interval));
    }
}

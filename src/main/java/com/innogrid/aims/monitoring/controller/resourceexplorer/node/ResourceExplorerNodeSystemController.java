package com.innogrid.aims.monitoring.controller.resourceexplorer.node;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.system.SystemSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeSystemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > 노드 System 카테고리 패널 API.
 *
 * 트리에서 노드 하위 System 항목을 선택했을 때 오른쪽 패널의 탭별 데이터를 제공한다.
 *   개요 탭     — KPI 카드(가동 시간, 부하 1/5/15, 코어 수, 컨텍스트 스위치·인터럽트), 시스템 정보 표(호스트명·OS·커널·아키텍처·부팅 시각)
 *   모니터링 탭 — 지표 5종 시계열 차트
 * 경로 패턴은 다른 카테고리와 동일하게 /node/{nodeId}/{category}/summary|info|metrics/trend 를 따른다.
 * 노드 상세 패널은 ResourceExplorerNodeController 가 담당한다. 스코프 밖 노드는 404 다.
 */
@Tag(name = "관측 > 자원 탐색기 (노드 System)", description = "노드 System 카테고리 — KPI 카드, 시스템 정보, 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node/{nodeId}/system")
public class ResourceExplorerNodeSystemController {

    private final ResourceExplorerNodeSystemService resourceExplorerNodeSystemService;

    public ResourceExplorerNodeSystemController(ResourceExplorerNodeSystemService resourceExplorerNodeSystemService) {
        this.resourceExplorerNodeSystemService = resourceExplorerNodeSystemService;
    }

    @Operation(summary = "System 개요 KPI",
            description = "개요 탭 카드 — 가동 시간(초, 프론트가 일/시간 표기), 부하 평균 1/5/15분, 코어 수, "
                    + "컨텍스트 스위치·인터럽트(/s, 최근 5분 rate). 미수집 값은 null 입니다.")
    @GetMapping("/summary")
    public ApiResponse<SystemSummaryResponse> getSystemSummary(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeSystemService.getSystemSummary(nodeId));
    }

    @Operation(summary = "System 정보",
            description = "개요 탭 정보 표 — 호스트명, OS 이름·버전, 커널 릴리스, 아키텍처, 부팅 시각, 수집 상태, 마지막 수집. "
                    + "node_uname_info·node_os_info 라벨 기준이며 exporter 가 없으면 NOT_CONFIGURED, 표본이 없으면 UNAVAILABLE 입니다.")
    @GetMapping("/info")
    public ApiResponse<SystemInfoResponse> getSystemInfo(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeSystemService.getSystemInfo(nodeId));
    }

    @Operation(summary = "System 모니터링 차트",
            description = "모니터링 탭 — metric(LOAD_1|LOAD_5|LOAD_15|CONTEXT_SWITCHES|INTERRUPTS)별 시계열을 조회합니다. "
                    + "부하는 단위 없음, 컨텍스트 스위치·인터럽트는 /s 입니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 조회 기간(지금 기준)이며 "
                    + "버킷 간격은 기간별 기본값(30s/5m/15m/1h/1d/3d)입니다. 미수집이면 빈 시리즈입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<SystemMetricTrendResponse> getSystemMetricTrend(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "LOAD_1") SystemMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerNodeSystemService.getSystemMetricTrend(nodeId, metric, interval));
    }
}

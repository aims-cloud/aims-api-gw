package com.innogrid.aims.monitoring.controller.resourceexplorer.node;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeProcessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > 노드 Process 카테고리 패널 API.
 *
 * 트리에서 노드 하위 Process 항목을 선택했을 때 오른쪽 패널의 탭별 데이터를 제공한다.
 *   개요 탭     — KPI 카드(실행 중·블록·전체 프로세스·스레드·fork 발생률)와 상태별 프로세스 수
 *   모니터링 탭 — 지표 5종 시계열 차트
 * PID 별 프로세스 목록·명령행은 기본 node_exporter 로 얻을 수 없어 제공하지 않는다(docs/06 §5.4).
 * 경로 패턴은 다른 카테고리와 동일하게 /node/{nodeId}/{category}/summary|metrics/trend 를 따른다. 스코프 밖 노드는 404 다.
 */
@Tag(name = "관측 > 자원 탐색기 (노드 Process)", description = "노드 Process 카테고리 — KPI 카드, 상태별 개수, 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node/{nodeId}/process")
public class ResourceExplorerNodeProcessController {

    private final ResourceExplorerNodeProcessService resourceExplorerNodeProcessService;

    public ResourceExplorerNodeProcessController(ResourceExplorerNodeProcessService resourceExplorerNodeProcessService) {
        this.resourceExplorerNodeProcessService = resourceExplorerNodeProcessService;
    }

    @Operation(summary = "Process 개요 KPI",
            description = "개요 탭 카드 — 실행 중·블록 프로세스 수, 전체 프로세스·스레드 수, fork 발생률(/s, 최근 5분 rate), "
                    + "상태별 프로세스 수(R/S/D/Z/T …). 전체 프로세스·스레드·상태별은 node_exporter 에 --collector.processes 가 켜진 노드에서만 "
                    + "값이 있고, 없으면 null/빈 목록입니다.")
    @GetMapping("/summary")
    public ApiResponse<ProcessSummaryResponse> getProcessSummary(@PathVariable String nodeId) {
        return ApiResponse.ok(resourceExplorerNodeProcessService.getProcessSummary(nodeId));
    }

    @Operation(summary = "Process 모니터링 차트",
            description = "모니터링 탭 — metric(PROCS_RUNNING|PROCS_BLOCKED|PROCESSES_TOTAL|THREADS|FORKS_RATE)별 시계열을 조회합니다. "
                    + "개수는 정수, FORKS_RATE 는 /s 입니다. PROCESSES_TOTAL·THREADS 는 processes 콜렉터가 없는 노드에서 빈 시리즈입니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 조회 기간(지금 기준)이며 "
                    + "버킷 간격은 기간별 기본값(30s/5m/15m/1h/1d/3d)입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<ProcessMetricTrendResponse> getProcessMetricTrend(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "PROCS_RUNNING") ProcessMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerNodeProcessService.getProcessMetricTrend(nodeId, metric, interval));
    }
}

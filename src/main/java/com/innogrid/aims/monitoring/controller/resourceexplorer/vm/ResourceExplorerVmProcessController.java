package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.process.ProcessSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerVmProcessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > VM Process 카테고리 패널 API.
 *
 * 트리에서 VM 하위 Process 항목을 선택했을 때 오른쪽 패널의 탭별 데이터를 제공한다.
 *   개요 탭     — KPI 카드(실행 중·블록·전체 프로세스·스레드·fork 발생률)와 상태별 프로세스 수
 *   모니터링 탭 — 지표 5종 시계열 차트
 * PID 별 프로세스 목록·명령행은 node_exporter 로 얻을 수 없어 제공하지 않는다(docs/06 §5.4).
 * VM 은 다른 VM 패널과 같이 nsId·infraId·vmId 쿼리 파라미터로 식별한다(트리 VIRTUAL_MACHINE 노드의 같은 이름 필드).
 * 응답 형식은 노드 Process 패널(ResourceExplorerNodeProcessController)과 같다. 스코프 밖·없는 VM 은 404 다.
 */
@Tag(name = "관측 > 자원 탐색기 (VM Process)", description = "VM Process 카테고리 — KPI 카드, 상태별 개수, 모니터링 차트")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/vm/process")
public class ResourceExplorerVmProcessController {

    private final ResourceExplorerVmProcessService resourceExplorerVmProcessService;

    public ResourceExplorerVmProcessController(ResourceExplorerVmProcessService resourceExplorerVmProcessService) {
        this.resourceExplorerVmProcessService = resourceExplorerVmProcessService;
    }

    @Operation(summary = "VM Process 개요 KPI",
            description = "개요 탭 카드 — 실행 중·블록 프로세스 수, 전체 프로세스·스레드 수, fork 발생률(/s, 최근 5분 rate), "
                    + "상태별 프로세스 수(R/S/D/Z/T …). VM 안 node_exporter 를 tb_ns/tb_infra/tb_node 라벨로 조회합니다. "
                    + "전체 프로세스·스레드·상태별은 --collector.processes 가 켜진 VM(docs/07 배포 스크립트 기본)에서만 값이 있고, "
                    + "없으면 null/빈 목록입니다.")
    @GetMapping("/summary")
    public ApiResponse<ProcessSummaryResponse> getProcessSummary(@RequestParam(required = false) String roleName,
                                                                 @RequestParam String nsId,
                                                                 @RequestParam String infraId,
                                                                 @RequestParam String vmId) {
        return ApiResponse.ok(resourceExplorerVmProcessService.getProcessSummary(roleName, nsId, infraId, vmId));
    }

    @Operation(summary = "VM Process 모니터링 차트",
            description = "모니터링 탭 — metric(PROCS_RUNNING|PROCS_BLOCKED|PROCESSES_TOTAL|THREADS|FORKS_RATE)별 시계열을 조회합니다. "
                    + "개수는 정수, FORKS_RATE 는 /s 입니다. PROCESSES_TOTAL·THREADS 는 processes 콜렉터가 없는 VM 에서 빈 시리즈입니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 조회 기간(지금 기준)이며 "
                    + "버킷 간격은 기간별 기본값(30s/5m/15m/1h/1d/3d)입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<ProcessMetricTrendResponse> getProcessMetricTrend(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @RequestParam(defaultValue = "PROCS_RUNNING") ProcessMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerVmProcessService.getProcessMetricTrend(roleName, nsId, infraId, vmId, metric, interval));
    }
}

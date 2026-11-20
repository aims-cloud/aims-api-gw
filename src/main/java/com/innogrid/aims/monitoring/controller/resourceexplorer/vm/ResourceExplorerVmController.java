package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeDetailMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.node.NodeMetricTrendResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmInfoResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vm.VmSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerVmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > VM 상세 패널 API.
 *
 * 트리에서 가상머신(Tumblebug Node)을 선택했을 때 오른쪽 패널(개요·모니터링 탭)의 데이터를 제공한다.
 *   개요 탭     — 주요 지표 카드 5종, 모니터링 요약 차트 6종, VM 정보 표
 *   모니터링 탭 — 지표별 시계열 (개요 요약 차트와 metrics/trend 공용)
 * VM 은 nsId·infraId·vmId 쿼리 파라미터로 식별한다 — 트리 VIRTUAL_MACHINE 노드의 같은 이름 필드를 그대로 넘긴다.
 * vmId 는 Tumblebug 의 node id 이며, 물리 노드 패널의 nodeId(assets.id)와 구분하기 위해 이름을 vmId 로 둔다
 * (Tumblebug ID 에 '/' 가 올 수 있어 경로 변수 대신 쿼리 파라미터를 쓴다).
 * 접근 권한은 nsId 가 토큰 역할(roleName 지정 시 그 역할)의 role_namespaces 매핑에 있는지로 판정하며, 없으면 404 다.
 * VM 하위 카테고리(CPU/Disk/…/GPU)는 화면 확정 시 노드 카테고리 패턴으로 별도 추가한다.
 * NodeGroup 패널은 ResourceExplorerNodeGroupController 가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (VM)", description = "VM 상세 — 주요 지표, 모니터링 요약 차트, VM 정보")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/vm")
public class ResourceExplorerVmController {

    private final ResourceExplorerVmService resourceExplorerVmService;

    public ResourceExplorerVmController(ResourceExplorerVmService resourceExplorerVmService) {
        this.resourceExplorerVmService = resourceExplorerVmService;
    }

    @Operation(summary = "VM 주요 지표",
            description = "개요 탭 카드 5종 — CPU/Memory/File System 사용률(용량 카드는 Free/Total GB 포함), "
                    + "네트워크 수신·송신(bytes/sec, 단위 변환은 프론트), GPU 사용률(VRAM Free/Total GB 병기), "
                    + "최근 업데이트 시각을 조회합니다. 카드가 null 이면 미수집입니다.")
    @GetMapping("/summary")
    public ApiResponse<VmSummaryResponse> getVmSummary(@RequestParam(required = false) String roleName,
                                                       @RequestParam String nsId,
                                                       @RequestParam String infraId,
                                                       @RequestParam String vmId) {
        return ApiResponse.ok(resourceExplorerVmService.getVmSummary(roleName, nsId, infraId, vmId));
    }

    @Operation(summary = "VM 모니터링 차트",
            description = "metric(CPU|MEMORY|FILE_SYSTEM|NETWORK|GPU|VRAM)별 시계열 — 개요 탭 모니터링 요약과 "
                    + "모니터링 탭에 공용입니다. metric 미지정 시 6종 전부를 반환하고, "
                    + "NETWORK 는 receive/transmit 2개 시리즈를 반환합니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 조회 기간(지금 기준)이며 "
                    + "버킷 간격은 기간별 기본값(30s/5m/15m/1h/1d/3d)입니다.")
    @GetMapping("/metrics/trend")
    public ApiResponse<List<NodeMetricTrendResponse>> getVmMetricTrend(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam String vmId,
            @RequestParam(required = false) NodeDetailMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval) {
        return ApiResponse.ok(resourceExplorerVmService.getVmMetricTrend(roleName, nsId, infraId, vmId, metric, interval));
    }

    @Operation(summary = "VM 정보",
            description = "개요 탭 VM 정보 표 — Tumblebug Node 상세 기준으로 이름/Namespace/Infra/NodeGroup/상태 원문/"
                    + "프로바이더·connectionName/region·zone/CSP 자원 ID/IP(public, private)/사양(vCPU, 메모리, 루트 디스크)/"
                    + "OS/생성 시각을 조회합니다.")
    @GetMapping("/info")
    public ApiResponse<VmInfoResponse> getVmInfo(@RequestParam(required = false) String roleName,
                                                 @RequestParam String nsId,
                                                 @RequestParam String infraId,
                                                 @RequestParam String vmId) {
        return ApiResponse.ok(resourceExplorerVmService.getVmInfo(roleName, nsId, infraId, vmId));
    }
}

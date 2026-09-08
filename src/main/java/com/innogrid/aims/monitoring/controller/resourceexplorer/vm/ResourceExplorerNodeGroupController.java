package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeSearchField;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.VmListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.VmTopResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerNodeGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > NodeGroup 패널 API (Tumblebug 가상머신 가지).
 *
 * 트리에서 Namespace → Infra 하위의 NodeGroup 또는 미분류 노드를 선택했을 때 오른쪽 개요 탭에 표시되는
 * KPI 카드(VM 수/Running/Pending/Stop/Error), VM Top-N, VM 목록을 제공한다.
 * nsId·infraId 는 필수, nodeGroupId 는 생략하면 Infra 전체, {@code @unclassified} 면 그룹 없는 VM 만이다.
 * 조회 대상은 현재 사용자 토큰의 역할에 매핑된 Namespace 로 제한된다(트리와 동일) — 스코프 밖이면 빈 결과다.
 * MDC 패널(ResourceExplorerMdcController)과 동형 구조이며, 알람 탭은 AlarmHistoryController(/histories?resourceId=)를 재사용한다.
 */
@Tag(name = "관측 > 자원 탐색기 (NodeGroup)", description = "Tumblebug NodeGroup 개요 KPI, VM Top-N, VM 목록 조회")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node-group")
public class ResourceExplorerNodeGroupController {

    private final ResourceExplorerNodeGroupService resourceExplorerNodeGroupService;

    public ResourceExplorerNodeGroupController(ResourceExplorerNodeGroupService resourceExplorerNodeGroupService) {
        this.resourceExplorerNodeGroupService = resourceExplorerNodeGroupService;
    }

    @Operation(summary = "NodeGroup 개요 KPI",
            description = "선택한 NodeGroup(nsId·infraId·nodeGroupId)의 VM 수, Running, Pending, Stop, Error 카드 값을 조회합니다. "
                    + "상태는 Tumblebug 상태값을 Running→RUNNING, Creating·Rebooting 등→PENDING, Suspended·Terminated→STOP, "
                    + "Failed·Undefined→ERROR 로 묶습니다. nodeGroupId 생략 시 Infra 전체, '@unclassified' 면 그룹 없는 VM 만입니다.")
    @GetMapping("/summary")
    public ApiResponse<NodeGroupSummaryResponse> getNodeGroupSummary(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam(required = false) String nodeGroupId) {
        return ApiResponse.ok(resourceExplorerNodeGroupService.getNodeGroupSummary(roleName, nsId, infraId, nodeGroupId));
    }

    @Operation(summary = "NodeGroup VM Top-N",
            description = "NodeGroup 내에서 metric(CPU|MEMORY|FILE_SYSTEM) 기준 상위 VM 을 조회합니다. "
                    + "행마다 세 지표를 모두 담아 지표 토글 시 재조회가 필요 없습니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H|LAST_30D|LAST_90D)은 Thanos 기간 평균 윈도우입니다. "
                    + "요청 지표가 미수집인 VM(tb_* 라벨 exporter 미등록)은 제외됩니다.")
    @GetMapping("/top")
    public ApiResponse<List<VmTopResponse>> getNodeGroupTop(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam(required = false) String nodeGroupId,
            @RequestParam(defaultValue = "CPU") NodeMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        return ApiResponse.ok(resourceExplorerNodeGroupService.getNodeGroupTop(
                roleName, nsId, infraId, nodeGroupId, metric, interval, limit));
    }

    @Operation(summary = "NodeGroup VM 목록",
            description = "NodeGroup 내 VM 목록 — 이름/상태(버킷·Tumblebug 원문)/CSP/IP(public·private)/CPU·Memory·File system 사용률(최근 10분 평균). "
                    + "사용률이 null 이면 미수집(\"-\")입니다. "
                    + "상태 필터와 검색(searchField 로 NAME|IP 선택 + keyword), 컬럼 정렬, 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/vms")
    public ApiResponse<PageResponse<VmListItemResponse>> getNodeGroupVms(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId,
            @RequestParam(required = false) String nodeGroupId,
            @RequestParam(required = false) NodeStatus status,
            @RequestParam(defaultValue = "NAME") NodeSearchField searchField,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerNodeGroupService.getNodeGroupVms(
                roleName, nsId, infraId, nodeGroupId, status, searchField, keyword, page, size, sort));
    }
}

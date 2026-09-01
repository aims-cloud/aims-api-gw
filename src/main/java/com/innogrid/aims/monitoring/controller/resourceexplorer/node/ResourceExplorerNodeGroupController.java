package com.innogrid.aims.monitoring.controller.resourceexplorer.node;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeMetricInterval;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeSearchField;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.nodegroup.NodeTopResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.node.ResourceExplorerNodeGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > 노드그룹 패널 API.
 *
 * 트리에서 배포영역별 노드그룹을 선택했을 때 오른쪽 개요 탭에 표시되는
 * KPI 카드(노드 수/Running/Pending/Stop/Error), 노드 Top-N, 노드 목록을 제공한다.
 * 조회 대상은 현재 사용자 토큰에 부여된 역할 스코프의 노드로 제한된다(트리와 동일) —
 * roleId/deploymentId 는 스코프 안에서의 필터이며, 토큰에 없는 역할을 지정하면 빈 결과다.
 * 왼쪽 트리는 ResourceExplorerTreeController 가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (노드그룹)", description = "노드그룹 개요 KPI, 노드 Top-N, 노드 목록 조회")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node-group")
public class ResourceExplorerNodeGroupController {

    private final ResourceExplorerNodeGroupService resourceExplorerNodeGroupService;

    public ResourceExplorerNodeGroupController(ResourceExplorerNodeGroupService resourceExplorerNodeGroupService) {
        this.resourceExplorerNodeGroupService = resourceExplorerNodeGroupService;
    }

    @Operation(summary = "노드그룹 개요 KPI",
            description = "선택한 노드그룹(역할·배포영역)의 노드 수, Running, Pending, Stop, Error 카드 값을 조회합니다.")
    @GetMapping("/summary")
    public ApiResponse<NodeGroupSummaryResponse> getNodeGroupSummary(
            @RequestParam(required = false) String roleId,
            @RequestParam(required = false) String deploymentId) {
        return ApiResponse.ok(resourceExplorerNodeGroupService.getNodeGroupSummary(roleId, deploymentId));
    }

    @Operation(summary = "노드그룹 노드 Top-N",
            description = "노드그룹 내에서 metric(CPU|MEMORY|FILE_SYSTEM) 기준 상위 노드를 조회합니다. "
                    + "행마다 세 지표를 모두 담아 지표 토글 시 재조회가 필요 없습니다. "
                    + "interval(LAST_10M|LAST_1H|LAST_6H|LAST_24H)은 사용률의 평균 집계 기간입니다 — 기간 변경 시 재조회합니다.")
    @GetMapping("/top")
    public ApiResponse<List<NodeTopResponse>> getNodeGroupTop(
            @RequestParam(required = false) String roleId,
            @RequestParam(required = false) String deploymentId,
            @RequestParam(defaultValue = "CPU") NodeMetric metric,
            @RequestParam(defaultValue = "LAST_10M") NodeMetricInterval interval,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        return ApiResponse.ok(resourceExplorerNodeGroupService.getNodeGroupTop(roleId, deploymentId, metric, interval, limit));
    }

    @Operation(summary = "노드그룹 노드 목록",
            description = "노드그룹 내 노드 목록 — 호스트명/별칭/상태/IP 주소(복수)/CPU·Memory·File system 사용률. "
                    + "사용률이 null 이면 미수집(\"-\")입니다. "
                    + "상태 필터와 검색(searchField 로 NAME|ALIAS|IP 중 대상 필드 선택 + keyword), "
                    + "컬럼 정렬, 페이징을 지원합니다. page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/nodes")
    public ApiResponse<PageResponse<NodeListItemResponse>> getNodeGroupNodes(
            @RequestParam(required = false) String roleId,
            @RequestParam(required = false) String deploymentId,
            @RequestParam(required = false) NodeStatus status,
            @RequestParam(defaultValue = "NAME") NodeSearchField searchField,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerNodeGroupService.getNodeGroupNodes(
                roleId, deploymentId, status, searchField, keyword, page, size, sort));
    }
}


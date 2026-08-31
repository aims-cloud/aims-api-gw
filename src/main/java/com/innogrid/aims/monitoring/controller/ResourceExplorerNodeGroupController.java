package com.innogrid.aims.monitoring.controller;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.NodeGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.NodeListItemResponse;
import com.innogrid.aims.monitoring.dto.NodeMetric;
import com.innogrid.aims.monitoring.dto.NodeStatus;
import com.innogrid.aims.monitoring.dto.NodeTopResponse;
import com.innogrid.aims.monitoring.service.ResourceExplorerNodeGroupService;
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
 * KPI 카드(노드 수/Running/Stop/Error), 노드 Top-N, 노드 목록을 제공한다.
 * 왼쪽 트리는 ResourceExplorerTreeController 가 담당한다.
 */
@Tag(name = "자원 탐색기 (노드그룹)", description = "노드그룹 개요 KPI, 노드 Top-N, 노드 목록 조회")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/node-group")
public class ResourceExplorerNodeGroupController {

    private final ResourceExplorerNodeGroupService resourceExplorerNodeGroupService;

    public ResourceExplorerNodeGroupController(ResourceExplorerNodeGroupService resourceExplorerNodeGroupService) {
        this.resourceExplorerNodeGroupService = resourceExplorerNodeGroupService;
    }

    @Operation(summary = "노드그룹 개요 KPI",
            description = "선택한 노드그룹(역할·배포영역)의 노드 수, Running, Stop, Error 카드 값을 조회합니다.")
    @GetMapping("/summary")
    public ApiResponse<NodeGroupSummaryResponse> getNodeGroupSummary(
            @RequestParam(required = false) String roleId,
            @RequestParam(required = false) String deploymentId) {
        return ApiResponse.ok(resourceExplorerNodeGroupService.getNodeGroupSummary(roleId, deploymentId));
    }

    @Operation(summary = "노드그룹 노드 Top-N",
            description = "노드그룹 내에서 metric(CPU|MEMORY|FILE_SYSTEM) 기준 상위 노드를 조회합니다. "
                    + "행마다 세 지표를 모두 담아 토글 시 재조회가 필요 없습니다.")
    @GetMapping("/top")
    public ApiResponse<List<NodeTopResponse>> getNodeGroupTop(
            @RequestParam(required = false) String roleId,
            @RequestParam(required = false) String deploymentId,
            @RequestParam(defaultValue = "CPU") NodeMetric metric,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        return ApiResponse.ok(resourceExplorerNodeGroupService.getNodeGroupTop(roleId, deploymentId, metric, limit));
    }

    @Operation(summary = "노드그룹 노드 목록",
            description = "노드그룹 내 노드 목록. 상태·키워드(이름/별칭/IP) 필터와 컬럼 정렬, 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/nodes")
    public ApiResponse<PageResponse<NodeListItemResponse>> getNodeGroupNodes(
            @RequestParam(required = false) String roleId,
            @RequestParam(required = false) String deploymentId,
            @RequestParam(required = false) NodeStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerNodeGroupService.getNodeGroupNodes(
                roleId, deploymentId, status, keyword, page, size, sort));
    }
}

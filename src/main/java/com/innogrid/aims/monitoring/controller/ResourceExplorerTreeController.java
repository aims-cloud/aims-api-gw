package com.innogrid.aims.monitoring.controller;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.ResourceTreeFilter;
import com.innogrid.aims.monitoring.dto.ResourceTreeNodeResponse;
import com.innogrid.aims.monitoring.dto.ResourceTreeNodeType;
import com.innogrid.aims.monitoring.dto.ResourceTreeSearchResponse;
import com.innogrid.aims.monitoring.service.ResourceExplorerTreeService;
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
 * 자원 탐색기 > 왼쪽 자원 트리 API.
 *
 * 역할을 루트로 물리 노드, 가상 머신 및 모니터링 항목의 계층 구조를 제공한다.
 * 노드그룹 선택 시 오른쪽 패널은 ResourceExplorerNodeGroupController 가 담당한다.
 */
@Tag(name = "자원 탐색기 (트리)", description = "역할·노드·가상 머신·모니터링 항목 트리 조회 및 검색")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/tree")
public class ResourceExplorerTreeController {

    private final ResourceExplorerTreeService resourceExplorerService;

    public ResourceExplorerTreeController(ResourceExplorerTreeService resourceExplorerService) {
        this.resourceExplorerService = resourceExplorerService;
    }

    @Operation(summary = "전체 자원 트리 조회",
            description = "필터 조건에 해당하는 역할과 하위 자원 계층을 전체 조회합니다.")
    @GetMapping
    public ApiResponse<ResourceTreeNodeResponse> getTree(
            @RequestParam(required = false) List<String> roleIds,
            @RequestParam(required = false) List<String> deploymentIds,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(resourceExplorerService.getTree(filter(roleIds, deploymentIds, keyword)));
    }

    @Operation(summary = "자원 트리 루트 조회",
            description = "Lazy Loading 방식의 트리 구성을 위한 최상위 역할 목록을 조회합니다.")
    @GetMapping("/roots")
    public ApiResponse<List<ResourceTreeNodeResponse>> getTreeRoots(
            @RequestParam(required = false) List<String> roleIds,
            @RequestParam(required = false) List<String> deploymentIds,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(resourceExplorerService.getRoots(filter(roleIds, deploymentIds, keyword)));
    }

    @Operation(summary = "자원 트리 자식 조회",
            description = "선택한 트리 항목의 직계 하위 항목을 조회합니다.")
    @GetMapping("/{treeNodeId}/children")
    public ApiResponse<List<ResourceTreeNodeResponse>> getTreeChildren(@PathVariable String treeNodeId) {
        return ApiResponse.ok(resourceExplorerService.getChildren(treeNodeId));
    }

    @Operation(summary = "자원 트리 검색",
            description = "자원 이름을 검색하고 검색 결과의 상위 경로를 함께 조회합니다.")
    @GetMapping("/search")
    public ApiResponse<List<ResourceTreeSearchResponse>> searchTree(
            @RequestParam String keyword,
            @RequestParam(required = false) List<ResourceTreeNodeType> types,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(resourceExplorerService.search(keyword, types, limit));
    }

    private ResourceTreeFilter filter(
            List<String> roleIds, List<String> deploymentIds, String keyword) {
        return new ResourceTreeFilter(roleIds, deploymentIds, keyword);
    }
}

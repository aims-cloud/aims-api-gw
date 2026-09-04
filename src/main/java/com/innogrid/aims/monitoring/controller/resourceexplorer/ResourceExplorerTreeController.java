package com.innogrid.aims.monitoring.controller.resourceexplorer;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeFilter;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeNodeType;
import com.innogrid.aims.monitoring.dto.resourceexplorer.tree.ResourceTreeSearchResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceExplorerTreeService;
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
 * 자원 탐색기 > 왼쪽 자원 트리 API (docs/06 자원 탐색기 트리 설계).
 *
 * 전체 자원 → 역할 → 물리 노드(MDC → 물리 호스트 → 모니터링 항목·가상자원) / 가상머신(Tumblebug) 계층을 제공한다.
 * 역할(ROLE)은 요청 파라미터가 아니라 현재 사용자 토큰의 권한에서 추출한다 —
 * 사용자는 자신에게 부여된 역할에 매핑된 자원 트리만 조회할 수 있다.
 * 화면 최초 로딩은 전체 트리를 한 번에 반환하고, roots/children 은 Lazy Loading 을 원하는 화면용 보조 API 다.
 */
@Tag(name = "관측 > 자원 탐색기 (트리)", description = "역할·MDC·물리 호스트·가상머신·모니터링 항목 트리 조회 및 검색")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/tree")
public class ResourceExplorerTreeController {

    private final ResourceExplorerTreeService resourceExplorerService;

    public ResourceExplorerTreeController(ResourceExplorerTreeService resourceExplorerService) {
        this.resourceExplorerService = resourceExplorerService;
    }

    @Operation(summary = "전체 자원 트리 조회",
            description = "현재 사용자 토큰의 역할에 매핑된 자원 계층을 모니터링 항목까지 전체 조회합니다. "
                    + "MDC·키워드 필터를 지원합니다. 사용률·시계열 값은 포함하지 않습니다.")
    @GetMapping
    public ApiResponse<ResourceTreeNodeResponse> getTree(
            @RequestParam(required = false) List<String> mdcIds,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(resourceExplorerService.getTree(filter(mdcIds, keyword)));
    }

    @Operation(summary = "자원 트리 루트 조회",
            description = "Lazy Loading 방식의 트리 구성을 위한 최상위 역할 목록 — "
                    + "현재 사용자 토큰에 부여된 역할만 조회됩니다.")
    @GetMapping("/roots")
    public ApiResponse<List<ResourceTreeNodeResponse>> getTreeRoots(
            @RequestParam(required = false) List<String> mdcIds,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(resourceExplorerService.getRoots(filter(mdcIds, keyword)));
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

    /** 역할은 서비스가 사용자 토큰에서 추출하므로 필터의 roleIds 는 사용하지 않는다 */
    private ResourceTreeFilter filter(List<String> mdcIds, String keyword) {
        return new ResourceTreeFilter(null, mdcIds, keyword);
    }
}

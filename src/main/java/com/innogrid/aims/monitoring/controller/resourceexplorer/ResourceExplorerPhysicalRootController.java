package com.innogrid.aims.monitoring.controller.resourceexplorer;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot.PhysicalRootMdcListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot.PhysicalRootSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceExplorerPhysicalRootService;
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
 * 자원 탐색기 > "물리 노드" 그룹 루트(PHYSICAL_ROOT) 패널 API.
 *
 * 트리에서 역할 하위 물리 노드 그룹({@code physical:{role}})을 선택했을 때 오른쪽 패널의 데이터를 제공한다.
 *   KPI 카드   — MDC 수, 물리 호스트 수, 1세부 가상자원 수, 호스트 Running/Pending/Stop/Error
 *   노드 상태   — 상태별 분포 도넛(역할 패널 node-status 와 같은 값)
 *   MDC 목록    — MDC 별 호스트 수·상태, 가상자원 수·상태 테이블 (행의 mdcId 로 트리 MDC 노드와 매칭)
 * 경로의 roleName 이 현재 사용자 토큰에 부여된 역할이 아니면 404. 역할 패널은 ResourceExplorerRoleController,
 * MDC 패널은 mdc 패키지 컨트롤러가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (물리 노드 그룹)", description = "역할 하위 물리 노드 그룹 루트 — KPI 카드, 노드 상태, MDC 목록")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/role/{roleName}/physical-root")
public class ResourceExplorerPhysicalRootController {

    private final ResourceExplorerPhysicalRootService resourceExplorerPhysicalRootService;

    public ResourceExplorerPhysicalRootController(ResourceExplorerPhysicalRootService resourceExplorerPhysicalRootService) {
        this.resourceExplorerPhysicalRootService = resourceExplorerPhysicalRootService;
    }

    @Operation(summary = "물리 노드 그룹 개요 KPI",
            description = "MDC 수, 물리 호스트 수, 호스트 위 1세부 가상자원 수와 호스트 Running/Pending/Stop/Error 카운트를 조회합니다. "
                    + "roleName 이 현재 사용자 토큰의 역할이 아니면 404 입니다.")
    @GetMapping("/summary")
    public ApiResponse<PhysicalRootSummaryResponse> getSummary(@PathVariable String roleName) {
        return ApiResponse.ok(resourceExplorerPhysicalRootService.getSummary(roleName));
    }

    @Operation(summary = "물리 노드 그룹 노드 상태",
            description = "노드 상태 도넛 — 총계(물리 호스트 수)와 Running/Error/Pending/Stop 상태별 카운트. 역할 패널 node-status 와 같은 값입니다.")
    @GetMapping("/node-status")
    public ApiResponse<ResourceStatusDistributionResponse> getNodeStatus(@PathVariable String roleName) {
        return ApiResponse.ok(resourceExplorerPhysicalRootService.getNodeStatus(roleName));
    }

    @Operation(summary = "물리 노드 그룹 MDC 목록",
            description = "MDC 테이블 — mdcId/이름/노드 수/노드 상태/가상자원 수/가상자원 상태. 상태 컬럼은 Running/Error/Pending/Stop "
                    + "스택바(자원이 없으면 0/0/0/0)입니다. 키워드(이름) 검색과 컬럼 정렬(name|nodeCount|vmCount), 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/mdcs")
    public ApiResponse<PageResponse<PhysicalRootMdcListItemResponse>> getMdcs(
            @PathVariable String roleName,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerPhysicalRootService.getMdcs(roleName, keyword, page, size, sort));
    }
}

package com.innogrid.aims.monitoring.controller.resourceexplorer;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleAreaListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.role.RoleSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceExplorerRoleService;
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
 * 자원 탐색기 > 역할 패널 API.
 *
 * 트리에서 역할을 선택했을 때 오른쪽 패널의 데이터를 제공한다.
 * 경로의 roleName 이 현재 사용자 토큰에 부여된 역할이 아니면 404 — 트리에 노출되지 않는 역할은 조회할 수 없다.
 *   KPI 카드         — 멤버/MDC/Namespace/노드/가상머신 수, Running/Pending/Stop/Error
 *   노드/가상머신 상태 — 상태별 분포 도넛 (전체 자원 패널과 동일 구조)
 *   자원 영역 섹션   — 역할 하위 MDC(물리)·Namespace(가상머신)별 노드/가상머신 수와 상태 스택바 테이블
 * 왼쪽 트리는 ResourceExplorerTreeController, 하위 패널은 MDC/NodeGroup 컨트롤러가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (역할)", description = "역할 패널 — KPI 카드, 노드/가상머신 상태, 자원 영역(MDC·Namespace) 목록")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/role/{roleName}")
public class ResourceExplorerRoleController {

    private final ResourceExplorerRoleService resourceExplorerRoleService;

    public ResourceExplorerRoleController(ResourceExplorerRoleService resourceExplorerRoleService) {
        this.resourceExplorerRoleService = resourceExplorerRoleService;
    }

    @Operation(summary = "역할 개요 KPI",
            description = "멤버 수, MDC 수, Namespace 수, 물리 호스트 수, 가상머신 수와 "
                    + "Running/Pending/Stop/Error 상태 카운트(호스트+가상머신 합산)를 조회합니다. "
                    + "roleName 이 현재 사용자 토큰의 역할이 아니면 404 입니다.")
    @GetMapping("/summary")
    public ApiResponse<RoleSummaryResponse> getRoleSummary(@PathVariable String roleName) {
        return ApiResponse.ok(resourceExplorerRoleService.getRoleSummary(roleName));
    }

    @Operation(summary = "역할 노드 상태",
            description = "노드 상태 도넛 — 총계(물리 호스트 수)와 Running/Error/Pending/Stop 상태별 카운트를 조회합니다.")
    @GetMapping("/node-status")
    public ApiResponse<ResourceStatusDistributionResponse> getNodeStatus(@PathVariable String roleName) {
        return ApiResponse.ok(resourceExplorerRoleService.getNodeStatus(roleName));
    }

    @Operation(summary = "역할 가상머신 상태",
            description = "가상머신 상태 도넛 — 총계(1세부 가상자원+Tumblebug VM)와 Running/Error/Pending/Stop 상태별 카운트를 조회합니다.")
    @GetMapping("/vm-status")
    public ApiResponse<ResourceStatusDistributionResponse> getVmStatus(@PathVariable String roleName) {
        return ApiResponse.ok(resourceExplorerRoleService.getVmStatus(roleName));
    }

    @Operation(summary = "역할 자원 영역 목록",
            description = "자원 영역 섹션 테이블 — 종류(MDC|NAMESPACE)/이름/노드 수/노드 상태/가상머신 수/가상머신 상태. "
                    + "MDC 행은 물리 호스트와 그 위의 1세부 가상자원, NAMESPACE 행은 Tumblebug VM 을 집계합니다. "
                    + "상태 컬럼은 Running/Error/Pending/Stop 스택바(자원이 없으면 0/0/0/0)입니다. "
                    + "키워드(이름) 검색과 컬럼 정렬(name|kind|nodeCount|vmCount), 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/areas")
    public ApiResponse<PageResponse<RoleAreaListItemResponse>> getRoleAreas(
            @PathVariable String roleName,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerRoleService.getRoleAreas(roleName, keyword, page, size, sort));
    }
}

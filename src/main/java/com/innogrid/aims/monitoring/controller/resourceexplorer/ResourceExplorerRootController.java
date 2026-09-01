package com.innogrid.aims.monitoring.controller.resourceexplorer;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RoleListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.root.RootSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceExplorerRootService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > 전체 자원(트리 루트) 패널 API.
 *
 * 트리에서 전체 자원을 선택했을 때 오른쪽 패널의 데이터를 제공한다.
 * "전체 자원"은 현재 사용자 토큰에 부여된 역할 스코프의 전체다 — 트리와 같은 범위를 집계한다.
 *   KPI 카드 8종     — 역할/배포영역/노드/가상머신 수, Running/Error/Pending/Stop
 *   노드/가상머신 상태 — 상태별 분포 도넛 (역할 패널과 동일 구조)
 *   역할 테이블      — 역할별 관리자/자원 수/상태 스택바 (행 클릭 시 역할 패널로 이동)
 * 역할 패널은 ResourceExplorerRoleController, 왼쪽 트리는 ResourceExplorerTreeController 가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (전체 자원)", description = "전체 자원 패널 — KPI 카드, 노드/가상머신 상태 도넛, 역할 테이블")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/root")
public class ResourceExplorerRootController {

    private final ResourceExplorerRootService resourceExplorerRootService;

    public ResourceExplorerRootController(ResourceExplorerRootService resourceExplorerRootService) {
        this.resourceExplorerRootService = resourceExplorerRootService;
    }

    @Operation(summary = "전체 자원 개요 KPI",
            description = "카드 8종 — 역할 수, 배포영역 수, 노드 수, 가상머신 수와 "
                    + "Running/Error/Pending/Stop 상태 카운트(노드+가상머신 합산)를 조회합니다. "
                    + "집계 범위는 현재 사용자 토큰의 역할 스코프입니다(트리와 동일).")
    @GetMapping("/summary")
    public ApiResponse<RootSummaryResponse> getRootSummary() {
        return ApiResponse.ok(resourceExplorerRootService.getRootSummary());
    }

    @Operation(summary = "전체 노드 상태",
            description = "노드 상태 도넛 — 총계(하이퍼바이저 수)와 Running/Error/Pending/Stop 상태별 카운트를 조회합니다.")
    @GetMapping("/node-status")
    public ApiResponse<ResourceStatusDistributionResponse> getNodeStatus() {
        return ApiResponse.ok(resourceExplorerRootService.getNodeStatus());
    }

    @Operation(summary = "전체 가상머신 상태",
            description = "가상머신 상태 도넛 — 총계와 Running/Error/Pending/Stop 상태별 카운트를 조회합니다.")
    @GetMapping("/vm-status")
    public ApiResponse<ResourceStatusDistributionResponse> getVmStatus() {
        return ApiResponse.ok(resourceExplorerRootService.getVmStatus());
    }

    @Operation(summary = "역할 목록",
            description = "역할 테이블 — 이름/관리자/멤버 수/배포영역 수/노드 수/노드 상태/가상머신 수/가상머신 상태. "
                    + "상태 컬럼은 Running/Error/Pending/Stop 스택바(값 없음은 null)입니다. "
                    + "키워드 검색과 컬럼 정렬, 페이징을 지원합니다. page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다. "
                    + "현재 사용자 토큰에 부여된 역할만 반환됩니다.")
    @GetMapping("/roles")
    public ApiResponse<PageResponse<RoleListItemResponse>> getRoles(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerRootService.getRoles(keyword, page, size, sort));
    }
}

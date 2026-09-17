package com.innogrid.aims.monitoring.controller.resourceexplorer;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.ResourceStatusDistributionResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot.VmRootNamespaceListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.grouproot.VmRootSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.ResourceExplorerVmRootService;
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
 * 자원 탐색기 > "가상머신" 그룹 루트(VM_ROOT) 패널 API.
 *
 * 트리에서 역할 하위 가상머신 그룹({@code vmroot:{role}})을 선택했을 때 오른쪽 패널의 데이터를 제공한다.
 *   KPI 카드        — Namespace 수, Infra 수, VM 수, VM Running/Pending/Stop/Error, Tumblebug 조회 실패 여부
 *   가상머신 상태   — 상태별 분포 도넛
 *   Namespace 목록  — Namespace 별 Infra 수·VM 수·VM 상태 테이블 (행의 nsId 로 트리 NAMESPACE 노드와 매칭)
 * 데이터는 트리·Namespace 패널과 같은 Tumblebug 스냅샷이다. 경로의 roleName 이 현재 사용자 토큰의 역할이 아니면 404.
 * 역할 패널은 ResourceExplorerRoleController, Namespace 패널은 vm 패키지 컨트롤러가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (가상머신 그룹)", description = "역할 하위 가상머신 그룹 루트 — KPI 카드, 가상머신 상태, Namespace 목록")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/role/{roleName}/vm-root")
public class ResourceExplorerVmRootController {

    private final ResourceExplorerVmRootService resourceExplorerVmRootService;

    public ResourceExplorerVmRootController(ResourceExplorerVmRootService resourceExplorerVmRootService) {
        this.resourceExplorerVmRootService = resourceExplorerVmRootService;
    }

    @Operation(summary = "가상머신 그룹 개요 KPI",
            description = "역할에 매핑된 Namespace 전체의 Namespace 수, Infra 수, VM 수와 VM Running/Pending/Stop/Error 카운트, "
                    + "tumblebugUnavailable(이번 갱신에서 Tumblebug 조회가 하나라도 실패해 이전 값이 섞여 있음)을 조회합니다. "
                    + "roleName 이 현재 사용자 토큰의 역할이 아니면 404 입니다.")
    @GetMapping("/summary")
    public ApiResponse<VmRootSummaryResponse> getSummary(@PathVariable String roleName) {
        return ApiResponse.ok(resourceExplorerVmRootService.getSummary(roleName));
    }

    @Operation(summary = "가상머신 그룹 가상머신 상태",
            description = "가상머신 상태 도넛 — 총계(Tumblebug VM 수)와 Running/Error/Pending/Stop 상태별 카운트를 조회합니다.")
    @GetMapping("/vm-status")
    public ApiResponse<ResourceStatusDistributionResponse> getVmStatus(@PathVariable String roleName) {
        return ApiResponse.ok(resourceExplorerVmRootService.getVmStatus(roleName));
    }

    @Operation(summary = "가상머신 그룹 Namespace 목록",
            description = "Namespace 테이블 — nsId/이름/Infra 수/VM 수/VM 상태/tumblebugUnavailable. 상태 컬럼은 Running/Error/Pending/Stop "
                    + "스택바(VM 이 없으면 0/0/0/0)입니다. 키워드(이름·nsId) 검색과 컬럼 정렬(name|infraCount|vmCount), 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/namespaces")
    public ApiResponse<PageResponse<VmRootNamespaceListItemResponse>> getNamespaces(
            @PathVariable String roleName,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerVmRootService.getNamespaces(roleName, keyword, page, size, sort));
    }
}

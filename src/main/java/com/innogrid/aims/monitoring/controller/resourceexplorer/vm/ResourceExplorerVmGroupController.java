package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeMetric;
import com.innogrid.aims.monitoring.dto.resourceexplorer.mdc.NodeStatus;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vmgroup.VmGroupSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vmgroup.VmListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.vmgroup.VmTopResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerVmGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > 가상머신 그룹 패널 API.
 *
 * 트리에서 배포영역별 가상머신 그룹을 선택했을 때 오른쪽 개요 탭에 표시되는
 * KPI 카드(가상머신 수/Running/Stop/Error), VM Top-N, VM 목록을 제공한다.
 * MDC 패널(ResourceExplorerMdcController)과 동형 구조이며,
 * 알람 탭은 기존 AlarmController 를 자원 스코프 필터로 재사용한다.
 */
@Tag(name = "관측 > 자원 탐색기 (가상머신 그룹)", description = "가상머신 그룹 개요 KPI, VM Top-N, VM 목록 조회")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/vm-group")
public class ResourceExplorerVmGroupController {

    private final ResourceExplorerVmGroupService resourceExplorerVmGroupService;

    public ResourceExplorerVmGroupController(ResourceExplorerVmGroupService resourceExplorerVmGroupService) {
        this.resourceExplorerVmGroupService = resourceExplorerVmGroupService;
    }

    @Operation(summary = "가상머신 그룹 개요 KPI",
            description = "선택한 가상머신 그룹(역할·배포영역)의 가상머신 수, Running, Stop, Error 카드 값을 조회합니다.")
    @GetMapping("/summary")
    public ApiResponse<VmGroupSummaryResponse> getVmGroupSummary(
            @RequestParam(required = false) String roleName,
            @RequestParam(required = false) String deploymentId) {
        return ApiResponse.ok(resourceExplorerVmGroupService.getVmGroupSummary(roleName, deploymentId));
    }

    @Operation(summary = "가상머신 그룹 VM Top-N",
            description = "가상머신 그룹 내에서 metric(CPU|MEMORY|FILE_SYSTEM) 기준 상위 VM 을 조회합니다. "
                    + "행마다 세 지표를 모두 담아 토글 시 재조회가 필요 없습니다.")
    @GetMapping("/top")
    public ApiResponse<List<VmTopResponse>> getVmGroupTop(
            @RequestParam(required = false) String roleName,
            @RequestParam(required = false) String deploymentId,
            @RequestParam(defaultValue = "CPU") NodeMetric metric,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        return ApiResponse.ok(resourceExplorerVmGroupService.getVmGroupTop(roleName, deploymentId, metric, limit));
    }

    @Operation(summary = "가상머신 그룹 VM 목록",
            description = "가상머신 그룹 내 VM 목록 — 이름/별칭/상태/IP/CPU/Memory(사용·전체)/File system. "
                    + "상태·키워드(이름/별칭/IP) 필터와 컬럼 정렬, 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/vms")
    public ApiResponse<PageResponse<VmListItemResponse>> getVmGroupVms(
            @RequestParam(required = false) String roleName,
            @RequestParam(required = false) String deploymentId,
            @RequestParam(required = false) NodeStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerVmGroupService.getVmGroupVms(
                roleName, deploymentId, status, keyword, page, size, sort));
    }
}

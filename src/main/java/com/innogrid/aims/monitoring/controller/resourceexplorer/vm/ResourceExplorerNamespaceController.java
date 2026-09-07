package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.dto.PageResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.namespace.InfraListItemResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.namespace.NamespaceSummaryResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerNamespaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > Namespace 패널 API (Tumblebug 가상머신 가지).
 *
 * 트리에서 가상머신 → Namespace({@code ns:{role}:{nsId}})를 선택했을 때 오른쪽 개요 탭의
 * KPI 카드(Infra 수/VM 수/Running/Pending/Stop/Error)와 Infra 목록을 제공한다.
 * nsId 는 필수, roleName 은 지정하면 그 역할 스코프로만 판정한다.
 * 조회 대상은 현재 사용자 토큰의 역할에 매핑된 Namespace 로 제한되며(트리와 동일) 스코프 밖이면 404 다.
 * 하위 Infra 는 ResourceExplorerInfraController, NodeGroup 은 ResourceExplorerNodeGroupController 가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (Namespace)", description = "Tumblebug Namespace 개요 KPI, Infra 목록 조회")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/namespace")
public class ResourceExplorerNamespaceController {

    private final ResourceExplorerNamespaceService resourceExplorerNamespaceService;

    public ResourceExplorerNamespaceController(ResourceExplorerNamespaceService resourceExplorerNamespaceService) {
        this.resourceExplorerNamespaceService = resourceExplorerNamespaceService;
    }

    @Operation(summary = "Namespace 개요 KPI",
            description = "선택한 Namespace(nsId)의 Infra 수, VM 수, Running, Pending, Stop, Error 카드 값을 조회합니다. "
                    + "상태는 Namespace 안 모든 VM 의 Tumblebug 상태값을 Running→RUNNING, Creating·Rebooting 등→PENDING, "
                    + "Suspended·Terminated→STOP, Failed·Undefined→ERROR 로 묶습니다. "
                    + "tumblebugUnavailable 이 true 면 이번 갱신에서 Tumblebug 조회에 실패해 이전 값(없으면 0)입니다.")
    @GetMapping("/summary")
    public ApiResponse<NamespaceSummaryResponse> getNamespaceSummary(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId) {
        return ApiResponse.ok(resourceExplorerNamespaceService.getNamespaceSummary(roleName, nsId));
    }

    @Operation(summary = "Namespace Infra 목록",
            description = "Namespace 안 Infra 목록 — 이름/Tumblebug 상태 원문(예: \"Running:1 (R:1/1)\")/VM 수/"
                    + "VM 상태 스택바(Running/Error/Pending/Stop, 자원이 없으면 0/0/0/0). "
                    + "키워드(이름·ID) 검색과 컬럼 정렬(name|tumblebugStatus|vmCount), 페이징을 지원합니다. "
                    + "page 는 1부터, sort 는 '{필드},{asc|desc}' 형식입니다.")
    @GetMapping("/infras")
    public ApiResponse<PageResponse<InfraListItemResponse>> getNamespaceInfras(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.ok(resourceExplorerNamespaceService.getNamespaceInfras(roleName, nsId, keyword, page, size, sort));
    }
}

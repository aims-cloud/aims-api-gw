package com.innogrid.aims.monitoring.controller.resourceexplorer.vm;

import java.util.List;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.infra.InfraSummaryResponse;
import com.innogrid.aims.monitoring.dto.resourceexplorer.infra.NodeGroupListItemResponse;
import com.innogrid.aims.monitoring.service.resourceexplorer.vm.ResourceExplorerInfraService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자원 탐색기 > Infra 패널 API (Tumblebug 가상머신 가지).
 *
 * 트리에서 Namespace → Infra({@code infra:{role}:{nsId}:{infraId}})를 선택했을 때 오른쪽 개요 탭의
 * 상태 카드(Tumblebug 상태 원문, VM 수/Running/Pending/Stop/Error)와 NodeGroup·미분류 목록을 제공한다.
 * nsId·infraId 는 필수, roleName 은 지정하면 그 역할 스코프로만 판정한다.
 * 조회 대상은 현재 사용자 토큰의 역할에 매핑된 Namespace 로 제한되며(트리와 동일) 스코프 밖이거나 Infra 가 없으면 404 다.
 * NodeGroup 상세는 ResourceExplorerNodeGroupController 가 담당한다.
 */
@Tag(name = "관측 > 자원 탐색기 (Infra)", description = "Tumblebug Infra 개요, NodeGroup·미분류 목록 조회")
@RestController
@RequestMapping("/api/v1/monitoring/resource-explorer/infra")
public class ResourceExplorerInfraController {

    private final ResourceExplorerInfraService resourceExplorerInfraService;

    public ResourceExplorerInfraController(ResourceExplorerInfraService resourceExplorerInfraService) {
        this.resourceExplorerInfraService = resourceExplorerInfraService;
    }

    @Operation(summary = "Infra 개요",
            description = "선택한 Infra(nsId·infraId)의 Tumblebug 집계 상태 원문(예: \"Running:1 (R:1/1)\"), "
                    + "VM 수, Running, Pending, Stop, Error 카드 값과 NodeGroup 수, 미분류 VM 수를 조회합니다. "
                    + "상태 카운트는 Infra 안 VM 의 Tumblebug 상태값을 Running→RUNNING, Creating·Rebooting 등→PENDING, "
                    + "Suspended·Terminated→STOP, Failed·Undefined→ERROR 로 묶은 값입니다.")
    @GetMapping("/summary")
    public ApiResponse<InfraSummaryResponse> getInfraSummary(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId) {
        return ApiResponse.ok(resourceExplorerInfraService.getInfraSummary(roleName, nsId, infraId));
    }

    @Operation(summary = "Infra NodeGroup 목록",
            description = "Infra 안 NodeGroup 목록 — 이름/VM 수/VM 상태 스택바(Running/Error/Pending/Stop). "
                    + "NodeGroup 이름순이며, nodeGroupId 가 없는 VM 이 있으면 미분류 행(nodeGroupId='@unclassified', unclassified=true)이 "
                    + "마지막에 붙습니다. 행의 nodeGroupId 를 NodeGroup 패널 API 에 그대로 넘기면 됩니다.")
    @GetMapping("/node-groups")
    public ApiResponse<List<NodeGroupListItemResponse>> getInfraNodeGroups(
            @RequestParam(required = false) String roleName,
            @RequestParam String nsId,
            @RequestParam String infraId) {
        return ApiResponse.ok(resourceExplorerInfraService.getInfraNodeGroups(roleName, nsId, infraId));
    }
}

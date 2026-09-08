package com.innogrid.aims.dashboard.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.openstack.service.OpenStackTopologyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 통합 토폴로지 API (탭클라우드잇 구조).
 * OpenStack 자원을 프로젝트 → 네트워크 → 서브넷 → VM 트리로 표출한다.
 *
 * 화면 컨트롤 대응:
 * - 배포영역 드롭다운: GET /deploy-zones (다중 연결 확장 전까지 1개)
 * - 컴퓨트/네트워크 토글: 응답 트리에서 프론트가 VM 레이어/네트워크 레이어를 표시·숨김 (재호출 불필요)
 * - 이름/IP 검색: 프론트에서 트리 필터링
 */
@Tag(name = "통합 토폴로지", description = "OpenStack 프로젝트→네트워크→서브넷→VM 트리")
@RestController
@RequestMapping("/api/v1/topology")
public class TopologyController {

    private final OpenStackTopologyService topologyService;

    public TopologyController(OpenStackTopologyService topologyService) {
        this.topologyService = topologyService;
    }

    @Operation(summary = "배포영역 목록", description = "토폴로지 상단 드롭다운. 1세부 OpenStack 연동 시 다중 연결로 확장")
    @GetMapping("/deploy-zones")
    public ApiResponse<List<Map<String, Object>>> listDeployZones() {
        return ApiResponse.ok(topologyService.listDeployZones());
    }

    @Operation(summary = "통합 토폴로지 트리",
            description = "networkLayer(프로젝트→라우터→네트워크→서브넷→포트) + computeLayer(가용영역→노드→프로젝트→VM). "
                    + "그룹 3종은 배열로 분리 제공: routersWithoutGateway(외부 게이트웨이 없는 라우터), "
                    + "unconnectedNetworks(라우터 미연결 네트워크), projectsWithoutVm(VM 없는 프로젝트). "
                    + "deployZone 파라미터는 다중 연결 확장 대비용으로 현재는 무시됨")
    @GetMapping
    public ApiResponse<Map<String, Object>> getTopology(
            @RequestParam(required = false) String deployZone) {
        return ApiResponse.ok(topologyService.getTopology());
    }
}

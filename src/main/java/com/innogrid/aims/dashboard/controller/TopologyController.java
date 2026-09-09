package com.innogrid.aims.dashboard.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.GatewayPortDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.HypervisorDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.NetworkDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.ProjectDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.RouterDetail;
import com.innogrid.aims.openstack.dto.TopologyDetailDtos.ServerDetail;
import com.innogrid.aims.openstack.dto.TopologyDtos.DeployZone;
import com.innogrid.aims.openstack.dto.TopologyDtos.TopologyTree;
import com.innogrid.aims.openstack.service.OpenStackTopologyDetailService;
import com.innogrid.aims.openstack.service.OpenStackTopologyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 통합 토폴로지 API (탭클라우드잇 구조).
 * OpenStack 자원을 프로젝트 → 네트워크 → 서브넷 → VM 트리로 표출하고,
 * 노드 클릭 시 상세 정보를 별도 엔드포인트로 제공한다.
 *
 * 화면 컨트롤 대응:
 * - 배포영역 드롭다운: GET /deploy-zones (다중 연결 확장 전까지 1개)
 * - 컴퓨트/네트워크 토글: 응답 트리에서 프론트가 레이어를 표시·숨김 (재호출 불필요)
 * - 이름/IP 검색: 프론트에서 트리 필터링 (멀티 OpenStack 확장 시 서버 검색 API 추가 예정)
 */
@Tag(name = "토폴로지 > 통합 토폴로지", description = "OpenStack 프로젝트→네트워크→서브넷→VM 트리 및 노드 상세")
@RestController
@RequestMapping("/api/v1/topology")
public class TopologyController {

    private final OpenStackTopologyService topologyService;
    private final OpenStackTopologyDetailService detailService;

    public TopologyController(OpenStackTopologyService topologyService,
                             OpenStackTopologyDetailService detailService) {
        this.topologyService = topologyService;
        this.detailService = detailService;
    }

    @Operation(summary = "배포영역 목록", description = "토폴로지 상단 드롭다운. 1세부 OpenStack 연동 시 다중 연결로 확장")
    @GetMapping("/deploy-zones")
    public ApiResponse<List<DeployZone>> listDeployZones() {
        return ApiResponse.ok(topologyService.listDeployZones());
    }

    @Operation(summary = "통합 토폴로지 트리",
            description = "networkLayer(프로젝트→라우터→네트워크→서브넷→포트) + computeLayer(가용영역→노드→프로젝트→VM). "
                    + "그룹 3종은 배열로 분리: routersWithoutGateway, unconnectedNetworks, projectsWithoutVm. "
                    + "deployZone 파라미터는 다중 연결 확장 대비용으로 현재는 무시됨")
    @GetMapping
    public ApiResponse<TopologyTree> getTopology(@RequestParam(required = false) String deployZone) {
        return ApiResponse.ok(topologyService.getTopology());
    }

    // ── 노드 클릭 상세 ──────────────────────────────────────────

    @Operation(summary = "라우터 상세", description = "상태/분산라우터/생성일/외부게이트웨이/내부 인터페이스/정적 경로")
    @GetMapping("/routers/{id}")
    public ApiResponse<RouterDetail> getRouterDetail(@PathVariable String id) {
        return ApiResponse.ok(detailService.getRouterDetail(id));
    }

    @Operation(summary = "네트워크 상세", description = "유형(geneve)/MTU/공유/서브넷(IP버전/DHCP/포트수)/포트 목록")
    @GetMapping("/networks/{id}")
    public ApiResponse<NetworkDetail> getNetworkDetail(@PathVariable String id) {
        return ApiResponse.ok(detailService.getNetworkDetail(id));
    }

    @Operation(summary = "외부 게이트웨이 포트 상세", description = "외부 IP/상태/MAC")
    @GetMapping("/ports/{id}")
    public ApiResponse<GatewayPortDetail> getPortDetail(@PathVariable String id) {
        return ApiResponse.ok(detailService.getPortDetail(id));
    }

    @Operation(summary = "가상머신 상세", description = "vCore/메모리/디스크/전원상태/이미지/사양/전체 IP/보안그룹 규칙")
    @GetMapping("/servers/{id}")
    public ApiResponse<ServerDetail> getServerDetail(@PathVariable String id) {
        return ApiResponse.ok(detailService.getServerDetail(id));
    }

    @Operation(summary = "물리노드·하이퍼바이저 상세", description = "가용영역/서비스/유형/vCPU·메모리·디스크 사용률/실행 VM수")
    @GetMapping("/hypervisors/{hostname}")
    public ApiResponse<HypervisorDetail> getHypervisorDetail(@PathVariable String hostname) {
        return ApiResponse.ok(detailService.getHypervisorDetail(hostname));
    }

    @Operation(summary = "프로젝트 상세", description = "도메인/상태/생성일/VM 목록")
    @GetMapping("/projects/{id}")
    public ApiResponse<ProjectDetail> getProjectDetail(@PathVariable String id) {
        return ApiResponse.ok(detailService.getProjectDetail(id));
    }
}

package com.innogrid.aims.infra.ipam.controller;

import java.security.Principal;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.HistoryRow;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.SubnetRequest;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.SubnetSummary;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Target;
import com.innogrid.aims.infra.ipam.service.NetworkAuditService;
import com.innogrid.aims.infra.ipam.service.NetworkSubnetService;
import com.innogrid.aims.infra.ipam.web.RequestContext;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 서브넷 API (IPAM).
 * 서브넷 CRUD + IP 이용률 + 행위이력. 독립 메뉴와 구역 하위 탭 공용.
 *
 * 포털 로그(docs/08 §10.1 묶음 3): 생성·수정·삭제는 {@code @PortalLog(AUDIT)}, 조회는 {@code ACCESS} 로 남긴다 (ref = SUBNET).
 * 도메인 자체 이력({@code NetworkAuditHistory})과 별개로 전 화면 통합 조회용이다.
 */
@Tag(name = "네트워크 > 서브넷", description = "IPAM 서브넷 관리 (IP 이용률 포함)")
@RestController
@RequestMapping("/api/v1/network/subnets")
public class NetworkSubnetController {

    private final NetworkSubnetService service;
    private final NetworkAuditService audit;

    public NetworkSubnetController(NetworkSubnetService service, NetworkAuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @Operation(summary = "서브넷 목록", description = "zoneId로 구역별 필터 가능")
    @PortalLog(type = PortalLogType.ACCESS, screen = "서브넷", action = "조회",
               content = "'서브넷 목록 조회 (구역: ' + (#zoneId ?: '전체') + ')'")
    @GetMapping
    public ApiResponse<List<SubnetSummary>> list(@RequestParam(required = false) Long zoneId) {
        return ApiResponse.ok(service.listSubnets(zoneId));
    }

    @Operation(summary = "서브넷 상세")
    @PortalLog(type = PortalLogType.ACCESS, screen = "서브넷", action = "조회",
               content = "'서브넷 조회: id ' + #subnetId",
               refType = "SUBNET", refId = "#subnetId")
    @GetMapping("/{subnetId}")
    public ApiResponse<SubnetSummary> get(@PathVariable Long subnetId) {
        return ApiResponse.ok(service.getSubnet(subnetId));
    }

    @Operation(summary = "서브넷 생성")
    @PortalLog(screen = "서브넷", action = "생성", content = "'서브넷 생성: ' + #req.cidr + ' (구역 id ' + #req.zoneId + ')'",
               refType = "SUBNET", refId = "#result.data.id")
    @PostMapping
    public ApiResponse<SubnetSummary> create(@RequestBody SubnetRequest req,
                                             Principal principal, HttpServletRequest http) {
        return ApiResponse.ok(service.createSubnet(req.zoneId(), req.cidr(), req.gateway(),
                req.description(), RequestContext.user(principal), RequestContext.clientIp(http)));
    }

    @Operation(summary = "서브넷 편집", description = "게이트웨이/설명 수정 (CIDR·구역은 불변)")
    @PortalLog(screen = "서브넷", action = "수정", content = "'서브넷 수정: id ' + #subnetId",
               refType = "SUBNET", refId = "#subnetId")
    @PutMapping("/{subnetId}")
    public ApiResponse<SubnetSummary> update(@PathVariable Long subnetId, @RequestBody SubnetRequest req,
                                             Principal principal, HttpServletRequest http) {
        return ApiResponse.ok(service.updateSubnet(subnetId, req.gateway(), req.description(),
                RequestContext.user(principal), RequestContext.clientIp(http)));
    }

    @Operation(summary = "서브넷 삭제")
    @PortalLog(screen = "서브넷", action = "삭제", content = "'서브넷 삭제: id ' + #subnetId",
               refType = "SUBNET", refId = "#subnetId")
    @DeleteMapping("/{subnetId}")
    public ApiResponse<Void> delete(@PathVariable Long subnetId,
                                    Principal principal, HttpServletRequest http) {
        service.deleteSubnet(subnetId, RequestContext.user(principal), RequestContext.clientIp(http));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "서브넷 행위 이력")
    @PortalLog(type = PortalLogType.ACCESS, screen = "서브넷", action = "조회",
               content = "'서브넷 이력 조회: id ' + #subnetId",
               refType = "SUBNET", refId = "#subnetId")
    @GetMapping("/{subnetId}/histories")
    public ApiResponse<List<HistoryRow>> histories(@PathVariable Long subnetId) {
        return ApiResponse.ok(audit.list(Target.SUBNET, subnetId));
    }
}

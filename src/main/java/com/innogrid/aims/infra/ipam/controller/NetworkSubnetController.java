package com.innogrid.aims.infra.ipam.controller;

import java.security.Principal;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.HistoryRow;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.SubnetRequest;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.SubnetSummary;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Target;
import com.innogrid.aims.infra.ipam.service.NetworkAuditService;
import com.innogrid.aims.infra.ipam.service.NetworkSubnetService;
import com.innogrid.aims.infra.ipam.web.RequestContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 서브넷 API (IPAM).
 * 서브넷 CRUD + IP 이용률 + 행위이력. 독립 메뉴와 구역 하위 탭 공용.
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
    @GetMapping
    public ApiResponse<List<SubnetSummary>> list(@RequestParam(required = false) Long zoneId) {
        return ApiResponse.ok(service.listSubnets(zoneId));
    }

    @Operation(summary = "서브넷 상세")
    @GetMapping("/{subnetId}")
    public ApiResponse<SubnetSummary> get(@PathVariable Long subnetId) {
        return ApiResponse.ok(service.getSubnet(subnetId));
    }

    @Operation(summary = "서브넷 생성")
    @PostMapping
    public ApiResponse<SubnetSummary> create(@RequestBody SubnetRequest req,
                                             Principal principal, HttpServletRequest http) {
        return ApiResponse.ok(service.createSubnet(req.zoneId(), req.cidr(), req.gateway(),
                req.description(), RequestContext.user(principal), RequestContext.clientIp(http)));
    }

    @Operation(summary = "서브넷 편집", description = "게이트웨이/설명 수정 (CIDR·구역은 불변)")
    @PutMapping("/{subnetId}")
    public ApiResponse<SubnetSummary> update(@PathVariable Long subnetId, @RequestBody SubnetRequest req,
                                             Principal principal, HttpServletRequest http) {
        return ApiResponse.ok(service.updateSubnet(subnetId, req.gateway(), req.description(),
                RequestContext.user(principal), RequestContext.clientIp(http)));
    }

    @Operation(summary = "서브넷 삭제")
    @DeleteMapping("/{subnetId}")
    public ApiResponse<Void> delete(@PathVariable Long subnetId,
                                    Principal principal, HttpServletRequest http) {
        service.deleteSubnet(subnetId, RequestContext.user(principal), RequestContext.clientIp(http));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "서브넷 행위 이력")
    @GetMapping("/{subnetId}/histories")
    public ApiResponse<List<HistoryRow>> histories(@PathVariable Long subnetId) {
        return ApiResponse.ok(audit.list(Target.SUBNET, subnetId));
    }
}

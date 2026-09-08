package com.innogrid.aims.infra.ipam.controller;

import java.security.Principal;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.HistoryRow;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.IpRow;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.ReserveIpRequest;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.UpdateIpRequest;
import com.innogrid.aims.infra.ipam.entity.IpAddress;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Target;
import com.innogrid.aims.infra.ipam.service.IpAddressService;
import com.innogrid.aims.infra.ipam.service.NetworkAuditService;
import com.innogrid.aims.infra.ipam.web.RequestContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * IP 주소 API (IPAM).
 * 할당됨(OpenStack 동기화) + 예약됨(수동 생성) IP를 관리한다.
 * 독립 메뉴와 서브넷 하위 탭 공용.
 */
@Tag(name = "IP 주소", description = "IPAM IP 주소 (예약/할당) 관리")
@RestController
@RequestMapping("/api/v1/network/ip-addresses")
public class IpAddressController {

    private final IpAddressService service;
    private final NetworkAuditService audit;

    public IpAddressController(IpAddressService service, NetworkAuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @Operation(summary = "IP 주소 목록",
            description = "필터: zoneId/subnetId/status(RESERVED|ALLOCATED)/searchField(IP|RESOURCE|DESCRIPTION)+keyword. 조회 시 OpenStack 할당 IP를 동기화")
    @GetMapping
    public ApiResponse<List<IpRow>> list(@RequestParam(required = false) Long zoneId,
                                         @RequestParam(required = false) Long subnetId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String searchField,
                                         @RequestParam(required = false) String keyword) {
        IpAddress.Status st = status == null || status.isBlank()
                ? null : IpAddress.Status.valueOf(status.toUpperCase());
        return ApiResponse.ok(service.listAll(zoneId, subnetId, st, searchField, keyword));
    }

    @Operation(summary = "서브넷의 IP 목록 (하위 탭)", description = "해당 서브넷만 동기화 후 반환")
    @GetMapping("/by-subnet/{subnetId}")
    public ApiResponse<List<IpRow>> listBySubnet(@PathVariable Long subnetId) {
        return ApiResponse.ok(service.listBySubnet(subnetId));
    }

    @Operation(summary = "IP 주소 상세")
    @GetMapping("/{ipId}")
    public ApiResponse<IpRow> get(@PathVariable Long ipId) {
        return ApiResponse.ok(service.getIp(ipId));
    }

    @Operation(summary = "IP 주소 예약 생성",
            description = "단일 또는 범위(ipStart~ipEnd) 예약. 예약 상태로 관리되며 자원에 즉시 할당되지 않음")
    @PostMapping
    public ApiResponse<Integer> reserve(@RequestBody ReserveIpRequest req,
                                        Principal principal, HttpServletRequest http) {
        int created = service.reserve(req.subnetId(), req.ipStart(), req.ipEnd(), req.description(),
                RequestContext.user(principal), RequestContext.clientIp(http));
        return ApiResponse.ok(created);
    }

    @Operation(summary = "IP 주소 편집 (별칭/설명)")
    @PutMapping("/{ipId}")
    public ApiResponse<IpRow> update(@PathVariable Long ipId, @RequestBody UpdateIpRequest req,
                                     Principal principal, HttpServletRequest http) {
        return ApiResponse.ok(service.update(ipId, req.alias(), req.description(),
                RequestContext.user(principal), RequestContext.clientIp(http)));
    }

    @Operation(summary = "IP 주소 삭제")
    @DeleteMapping("/{ipId}")
    public ApiResponse<Void> delete(@PathVariable Long ipId,
                                    Principal principal, HttpServletRequest http) {
        service.delete(ipId, RequestContext.user(principal), RequestContext.clientIp(http));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "IP 주소 행위 이력 (서브넷 단위)")
    @GetMapping("/histories")
    public ApiResponse<List<HistoryRow>> histories(@RequestParam Long subnetId) {
        return ApiResponse.ok(audit.list(Target.IP, subnetId));
    }
}

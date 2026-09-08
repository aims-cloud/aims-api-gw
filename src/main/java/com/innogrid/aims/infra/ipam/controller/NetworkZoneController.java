package com.innogrid.aims.infra.ipam.controller;

import java.security.Principal;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.HistoryRow;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.InterfaceCandidate;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.SaveInterfacesRequest;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.ZoneInterface;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.ZoneRequest;
import com.innogrid.aims.infra.ipam.dto.IpamDtos.ZoneSummary;
import com.innogrid.aims.infra.ipam.entity.NetworkAuditHistory.Target;
import com.innogrid.aims.infra.ipam.service.NetworkAuditService;
import com.innogrid.aims.infra.ipam.service.NetworkZoneService;
import com.innogrid.aims.infra.ipam.web.RequestContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 네트워크 구역 API (IPAM).
 * 구역 CRUD + 인터페이스 관리(OpenStack 네트워크 연결) + 행위이력.
 */
@Tag(name = "네트워크 구역", description = "IPAM 네트워크 구역 관리")
@RestController
@RequestMapping("/api/v1/network/zones")
public class NetworkZoneController {

    private final NetworkZoneService service;
    private final NetworkAuditService audit;

    public NetworkZoneController(NetworkZoneService service, NetworkAuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @Operation(summary = "구역 목록")
    @GetMapping
    public ApiResponse<List<ZoneSummary>> list() {
        return ApiResponse.ok(service.listZones());
    }

    @Operation(summary = "구역 상세")
    @GetMapping("/{zoneId}")
    public ApiResponse<ZoneSummary> get(@PathVariable Long zoneId) {
        return ApiResponse.ok(service.getZone(zoneId));
    }

    @Operation(summary = "구역 생성")
    @PostMapping
    public ApiResponse<ZoneSummary> create(@RequestBody ZoneRequest req,
                                           Principal principal, HttpServletRequest http) {
        return ApiResponse.ok(service.createZone(req.name(), req.description(),
                RequestContext.user(principal), RequestContext.clientIp(http)));
    }

    @Operation(summary = "구역 편집")
    @PutMapping("/{zoneId}")
    public ApiResponse<ZoneSummary> update(@PathVariable Long zoneId, @RequestBody ZoneRequest req,
                                           Principal principal, HttpServletRequest http) {
        return ApiResponse.ok(service.updateZone(zoneId, req.name(), req.description(),
                RequestContext.user(principal), RequestContext.clientIp(http)));
    }

    @Operation(summary = "구역 삭제")
    @DeleteMapping("/{zoneId}")
    public ApiResponse<Void> delete(@PathVariable Long zoneId,
                                    Principal principal, HttpServletRequest http) {
        service.deleteZone(zoneId, RequestContext.user(principal), RequestContext.clientIp(http));
        return ApiResponse.ok(null);
    }

    // ── 네트워크 인터페이스 탭 ──────────────────────────────────

    @Operation(summary = "인터페이스 관리 후보 (OpenStack 네트워크)")
    @GetMapping("/interface-candidates")
    public ApiResponse<List<InterfaceCandidate>> interfaceCandidates() {
        return ApiResponse.ok(service.listInterfaceCandidates());
    }

    @Operation(summary = "구역의 연결 인터페이스 목록")
    @GetMapping("/{zoneId}/interfaces")
    public ApiResponse<List<ZoneInterface>> interfaces(@PathVariable Long zoneId) {
        return ApiResponse.ok(service.listInterfaces(zoneId));
    }

    @Operation(summary = "인터페이스 저장 (전체 교체)")
    @PutMapping("/{zoneId}/interfaces")
    public ApiResponse<List<ZoneInterface>> saveInterfaces(@PathVariable Long zoneId,
                                                           @RequestBody SaveInterfacesRequest req,
                                                           Principal principal, HttpServletRequest http) {
        return ApiResponse.ok(service.saveInterfaces(zoneId, req.interfaces(),
                RequestContext.user(principal), RequestContext.clientIp(http)));
    }

    // ── 행위 이력 탭 ────────────────────────────────────────────

    @Operation(summary = "구역 행위 이력")
    @GetMapping("/{zoneId}/histories")
    public ApiResponse<List<HistoryRow>> histories(@PathVariable Long zoneId) {
        return ApiResponse.ok(audit.list(Target.ZONE, zoneId));
    }
}

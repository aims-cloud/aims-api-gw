package com.innogrid.aims.resource.controller;

import java.security.Principal;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.resource.dto.VmGroupDtos.AddServersRequest;
import com.innogrid.aims.resource.dto.VmGroupDtos.CandidateServer;
import com.innogrid.aims.resource.dto.VmGroupDtos.GroupRequest;
import com.innogrid.aims.resource.dto.VmGroupDtos.GroupServer;
import com.innogrid.aims.resource.dto.VmGroupDtos.GroupSummary;
import com.innogrid.aims.resource.dto.VmGroupDtos.HistoryRow;
import com.innogrid.aims.resource.dto.VmGroupDtos.NetworkMap;
import com.innogrid.aims.resource.dto.VmGroupDtos.NetworkMapRequest;
import com.innogrid.aims.resource.service.VmGroupService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 가상서버 그룹 API (통합 토폴로지 OpenStack 기준).
 * 그룹 CRUD + 가상서버 담기/빼기 + 네트워크 맵(서버 연결 관리) + 행위 이력.
 *
 * 포털 로그(docs/08 §10.1 묶음 6): 생성·수정·삭제·서버 추가/제거·네트워크 맵 저장은 {@code @PortalLog(AUDIT)}, 조회는 {@code ACCESS} 로
 * 남긴다(ref = VM_GROUP). 도메인 자체 이력({@code VmGroupActionHistory})과 별개로 전 화면 통합 조회용이다.
 */
@Tag(name = "토폴로지 > 가상서버 그룹", description = "OpenStack VM 논리 그룹·연결(네트워크 맵) 관리")
@RestController
@RequestMapping("/api/v1/vm-groups")
public class VmGroupController {

    private final VmGroupService service;

    public VmGroupController(VmGroupService service) {
        this.service = service;
    }

    // ── 그룹 CRUD ───────────────────────────────────────────────

    @Operation(summary = "그룹 목록")
    @PortalLog(type = PortalLogType.ACCESS, screen = "가상서버 그룹", action = "조회", content = "'가상서버 그룹 목록 조회'")
    @GetMapping
    public ApiResponse<List<GroupSummary>> listGroups() {
        return ApiResponse.ok(service.listGroups());
    }

    @Operation(summary = "그룹 상세 — 상세 탭")
    @PortalLog(type = PortalLogType.ACCESS, screen = "가상서버 그룹", action = "조회",
               content = "'가상서버 그룹 조회: id ' + #groupId", refType = "VM_GROUP", refId = "#groupId")
    @GetMapping("/{groupId}")
    public ApiResponse<GroupSummary> getGroup(@PathVariable Long groupId) {
        return ApiResponse.ok(service.getGroupSummary(groupId));
    }

    @Operation(summary = "그룹 생성")
    @PortalLog(screen = "가상서버 그룹", action = "생성", content = "'가상서버 그룹 생성: ' + #req.name + ' (배포영역 ' + #req.deployZone + ')'",
               refType = "VM_GROUP", refId = "#result.data.id")
    @PostMapping
    public ApiResponse<GroupSummary> createGroup(@RequestBody GroupRequest req,
                                                 Principal principal, HttpServletRequest http) {
        return ApiResponse.ok(service.createGroup(req.deployZone(), req.name(), req.description(),
                req.enabled() == null || req.enabled(), name(principal), clientIp(http)));
    }

    @Operation(summary = "그룹 편집")
    @PortalLog(screen = "가상서버 그룹", action = "수정", content = "'가상서버 그룹 수정: ' + #req.name + ' (id ' + #groupId + ')'",
               refType = "VM_GROUP", refId = "#groupId")
    @PutMapping("/{groupId}")
    public ApiResponse<GroupSummary> updateGroup(@PathVariable Long groupId, @RequestBody GroupRequest req,
                                                 Principal principal, HttpServletRequest http) {
        return ApiResponse.ok(service.updateGroup(groupId, req.deployZone(), req.name(),
                req.description(), req.enabled(), name(principal), clientIp(http)));
    }

    @Operation(summary = "그룹 삭제")
    @PortalLog(screen = "가상서버 그룹", action = "삭제", content = "'가상서버 그룹 삭제: id ' + #groupId",
               refType = "VM_GROUP", refId = "#groupId")
    @DeleteMapping("/{groupId}")
    public ApiResponse<Void> deleteGroup(@PathVariable Long groupId,
                                         Principal principal, HttpServletRequest http) {
        service.deleteGroup(groupId, name(principal), clientIp(http));
        return ApiResponse.ok(null);
    }

    // ── 가상서버 탭 ─────────────────────────────────────────────

    @Operation(summary = "그룹의 가상서버 목록 (실시간 상태)")
    @PortalLog(type = PortalLogType.ACCESS, screen = "가상서버 그룹", action = "조회",
               content = "'가상서버 그룹 서버 목록 조회: id ' + #groupId", refType = "VM_GROUP", refId = "#groupId")
    @GetMapping("/{groupId}/servers")
    public ApiResponse<List<GroupServer>> listServers(@PathVariable Long groupId) {
        return ApiResponse.ok(service.listServers(groupId));
    }

    @Operation(summary = "가상서버 추가 후보 목록", description = "배포영역의 OpenStack 서버 중 미담김 항목")
    @PortalLog(type = PortalLogType.ACCESS, screen = "가상서버 그룹", action = "조회",
               content = "'가상서버 그룹 추가 후보 조회: id ' + #groupId", refType = "VM_GROUP", refId = "#groupId")
    @GetMapping("/{groupId}/candidates")
    public ApiResponse<List<CandidateServer>> listCandidates(@PathVariable Long groupId) {
        return ApiResponse.ok(service.listCandidates(groupId));
    }

    @Operation(summary = "가상서버 추가")
    @PortalLog(screen = "가상서버 그룹", action = "서버 추가", content = "'가상서버 그룹 서버 추가: id ' + #groupId + ' (' + #req.serverIds.size() + '대: ' + #req.serverIds + ')'",
               refType = "VM_GROUP", refId = "#groupId")
    @PostMapping("/{groupId}/servers")
    public ApiResponse<Void> addServers(@PathVariable Long groupId, @RequestBody AddServersRequest req,
                                        Principal principal, HttpServletRequest http) {
        service.addServers(groupId, req.serverIds(), name(principal), clientIp(http));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "가상서버 제거")
    @PortalLog(screen = "가상서버 그룹", action = "서버 제거", content = "'가상서버 그룹 서버 제거: id ' + #groupId + ' (멤버 ' + #memberId + ')'",
               refType = "VM_GROUP", refId = "#groupId")
    @DeleteMapping("/{groupId}/servers/{memberId}")
    public ApiResponse<Void> removeServer(@PathVariable Long groupId, @PathVariable Long memberId,
                                          Principal principal, HttpServletRequest http) {
        service.removeServer(groupId, memberId, name(principal), clientIp(http));
        return ApiResponse.ok(null);
    }

    // ── 네트워크 맵 (서버 연결 관리) ────────────────────────────

    @Operation(summary = "네트워크 맵 조회", description = "그룹 내 VM 노드와 방향성 연결(엣지)")
    @PortalLog(type = PortalLogType.ACCESS, screen = "가상서버 그룹", action = "조회",
               content = "'가상서버 그룹 네트워크 맵 조회: id ' + #groupId", refType = "VM_GROUP", refId = "#groupId")
    @GetMapping("/{groupId}/network-map")
    public ApiResponse<NetworkMap> getNetworkMap(@PathVariable Long groupId) {
        return ApiResponse.ok(service.getNetworkMap(groupId));
    }

    @Operation(summary = "네트워크 맵 저장 (서버 연결 관리)", description = "연결 전체 교체")
    @PortalLog(screen = "가상서버 그룹", action = "네트워크 맵 저장", content = "'가상서버 그룹 네트워크 맵 저장: id ' + #groupId + ' (연결 ' + #req.edges.size() + '개)'",
               refType = "VM_GROUP", refId = "#groupId")
    @PutMapping("/{groupId}/network-map")
    public ApiResponse<NetworkMap> saveNetworkMap(@PathVariable Long groupId,
                                                  @RequestBody NetworkMapRequest req,
                                                  Principal principal, HttpServletRequest http) {
        return ApiResponse.ok(service.saveNetworkMap(groupId, req.edges(), name(principal), clientIp(http)));
    }

    // ── 행위 이력 탭 ────────────────────────────────────────────

    @Operation(summary = "행위 이력")
    @PortalLog(type = PortalLogType.ACCESS, screen = "가상서버 그룹", action = "조회",
               content = "'가상서버 그룹 이력 조회: id ' + #groupId", refType = "VM_GROUP", refId = "#groupId")
    @GetMapping("/{groupId}/histories")
    public ApiResponse<List<HistoryRow>> getHistories(@PathVariable Long groupId) {
        return ApiResponse.ok(service.getHistories(groupId));
    }

    // ── 공통 ────────────────────────────────────────────────────

    private static String name(Principal principal) {
        return principal == null ? null : principal.getName();
    }

    private static String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}

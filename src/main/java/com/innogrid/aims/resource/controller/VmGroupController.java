package com.innogrid.aims.resource.controller;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.resource.entity.VmGroup;
import com.innogrid.aims.resource.entity.VmGroupActionHistory;
import com.innogrid.aims.resource.entity.VmGroupMember;
import com.innogrid.aims.resource.service.VmGroupService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 가상서버 그룹 API.
 * 그룹 CRUD + 일괄 동작(시작/중지) + 상세(소속 가상서버/행위이력 탭).
 */
@Tag(name = "가상서버 그룹", description = "VM 논리 그룹 관리 및 일괄 동작")
@RestController
@RequestMapping("/api/v1/vm-groups")
public class VmGroupController {

    private final VmGroupService vmGroupService;

    public VmGroupController(VmGroupService vmGroupService) {
        this.vmGroupService = vmGroupService;
    }

    @Operation(summary = "그룹 목록")
    @GetMapping
    public ApiResponse<List<VmGroup>> listGroups() {
        return ApiResponse.ok(vmGroupService.listGroups());
    }

    @Operation(summary = "그룹 생성")
    @PostMapping
    public ApiResponse<VmGroup> createGroup(@RequestBody GroupRequest request, Principal principal) {
        return ApiResponse.ok(vmGroupService.createGroup(
                request.name(), request.description(), request.deployZone(),
                toMembers(request.members()), name(principal)));
    }

    @Operation(summary = "그룹 상세 — 소속 가상서버 탭 (VM별 현재 상태 포함)")
    @GetMapping("/{groupId}")
    public ApiResponse<Map<String, Object>> getGroupDetail(@PathVariable Long groupId) {
        return ApiResponse.ok(vmGroupService.getGroupDetail(groupId));
    }

    @Operation(summary = "그룹 수정")
    @PutMapping("/{groupId}")
    public ApiResponse<VmGroup> updateGroup(@PathVariable Long groupId,
                                            @RequestBody GroupRequest request,
                                            Principal principal) {
        return ApiResponse.ok(vmGroupService.updateGroup(
                groupId, request.name(), request.description(), request.deployZone(),
                request.members() == null ? null : toMembers(request.members()),
                name(principal)));
    }

    @Operation(summary = "그룹 삭제")
    @DeleteMapping("/{groupId}")
    public ApiResponse<Void> deleteGroup(@PathVariable Long groupId, Principal principal) {
        vmGroupService.deleteGroup(groupId, name(principal));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "그룹 시작 — 소속 VM 전체 resume")
    @PostMapping("/{groupId}/start")
    public ApiResponse<Map<String, Object>> startGroup(@PathVariable Long groupId, Principal principal) {
        return ApiResponse.ok(vmGroupService.startGroup(groupId, name(principal)));
    }

    @Operation(summary = "그룹 중지 — 소속 VM 전체 suspend")
    @PostMapping("/{groupId}/stop")
    public ApiResponse<Map<String, Object>> stopGroup(@PathVariable Long groupId, Principal principal) {
        return ApiResponse.ok(vmGroupService.stopGroup(groupId, name(principal)));
    }

    @Operation(summary = "그룹 상세 — 행위이력 탭")
    @GetMapping("/{groupId}/histories")
    public ApiResponse<List<VmGroupActionHistory>> getGroupHistories(@PathVariable Long groupId) {
        return ApiResponse.ok(vmGroupService.getGroupHistories(groupId));
    }

    // ── 요청 DTO ────────────────────────────────────────────────

    public record GroupRequest(String name, String description, String deployZone,
                               List<MemberRequest> members) {}

    public record MemberRequest(String connectionName, String vmName) {}

    private static List<VmGroupMember> toMembers(List<MemberRequest> requests) {
        if (requests == null) return List.of();
        return requests.stream()
                .map(m -> new VmGroupMember(m.connectionName(), m.vmName()))
                .toList();
    }

    private static String name(Principal principal) {
        return principal == null ? null : principal.getName();
    }
}

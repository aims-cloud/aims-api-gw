package com.innogrid.aims.auth.controller;

import java.util.List;

import com.innogrid.aims.auth.dto.AuthDto;
import com.innogrid.aims.auth.entity.Permission;
import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.repository.PermissionRepository;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.infra.service.RoleNamespaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 역할/권한 관리 API.
 * [요구사항 #7] 역할 관리
 * [요구사항 #8] 자원 권한 조회
 *
 * 포털 로그(docs/08 §10.1 묶음 2): 역할 생성·수정·삭제·권한 추가·Namespace 배정·해제는 {@code @PortalLog(AUDIT)}, 목록·상세·권한 조회는 {@code ACCESS} 로
 * 남긴다(ref = ROLE). Namespace 목록·후보 조회는 프론트가 아직 부르지 않아 화면이 붙을 때 함께 붙인다.
 * 조회는 남기지 않는다.
 */
@Tag(name = "역할/권한", description = "역할 CRUD, 권한 관리")
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RoleNamespaceService roleNamespaceService;

    public RoleController(RoleRepository roleRepository, PermissionRepository permissionRepository,
                          RoleNamespaceService roleNamespaceService) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.roleNamespaceService = roleNamespaceService;
    }

    @PortalLog(type = PortalLogType.ACCESS, screen = "역할/권한", action = "조회",
               content = "'역할 목록 조회'")
    @GetMapping
    public ApiResponse<List<AuthDto.RoleResponse>> listRoles() {
        List<AuthDto.RoleResponse> roles = roleRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
        return ApiResponse.ok(roles);
    }

    @PortalLog(screen = "역할/권한", action = "생성", content = "'역할 생성: ' + #request.name",
               refType = "ROLE", refId = "#result.data.id")
    @PostMapping
    public ApiResponse<AuthDto.RoleResponse> createRole(@RequestBody AuthDto.CreateRoleRequest request) {
        Role role = new Role(request.name(), request.description());
        roleRepository.save(role);
        return ApiResponse.ok(toResponse(role));
    }

    @PortalLog(type = PortalLogType.ACCESS, screen = "역할/권한", action = "조회",
               content = "'역할 조회: id ' + #roleId",
               refType = "ROLE", refId = "#roleId")
    @GetMapping("/{roleId}")
    public ApiResponse<AuthDto.RoleResponse> getRole(@PathVariable Long roleId) {
        Role role = findRole(roleId);
        return ApiResponse.ok(toResponse(role));
    }

    @PortalLog(screen = "역할/권한", action = "수정", content = "'역할 수정: ' + #request.name + ' (id ' + #roleId + ')'",
               refType = "ROLE", refId = "#roleId")
    @PutMapping("/{roleId}")
    public ApiResponse<AuthDto.RoleResponse> updateRole(@PathVariable Long roleId,
                                                        @RequestBody AuthDto.CreateRoleRequest request) {
        Role role = findRole(roleId);
        role.setName(request.name());
        role.setDescription(request.description());
        roleRepository.save(role);
        return ApiResponse.ok(toResponse(role));
    }

    @PortalLog(screen = "역할/권한", action = "삭제", content = "'역할 삭제: id ' + #roleId",
               refType = "ROLE", refId = "#roleId")
    @DeleteMapping("/{roleId}")
    public ApiResponse<Void> deleteRole(@PathVariable Long roleId) {
        roleNamespaceService.deleteRole(roleId);                // Namespace 배정이 남아 있으면 외래키로 삭제가 막히므로 함께 지운다
        return ApiResponse.ok(null);
    }

    @PortalLog(screen = "역할/권한", action = "권한 추가",
               content = "'권한 추가: 역할 id ' + #roleId + ', ' + #request.resource + ':' + #request.action",
               refType = "ROLE", refId = "#roleId")
    @PostMapping("/{roleId}/permissions")
    public ApiResponse<Void> addPermission(@PathVariable Long roleId,
                                           @RequestBody AuthDto.CreatePermissionRequest request) {
        Role role = findRole(roleId);
        Permission permission = new Permission(request.resource(), request.action(), request.description());
        permissionRepository.save(permission);
        role.addPermission(permission);
        roleRepository.save(role);
        return ApiResponse.ok(null);
    }

    @PortalLog(type = PortalLogType.ACCESS, screen = "역할/권한", action = "조회",
               content = "'역할 권한 조회: id ' + #roleId",
               refType = "ROLE", refId = "#roleId")
    @GetMapping("/{roleId}/permissions")
    public ApiResponse<?> getPermissions(@PathVariable Long roleId) {
        Role role = findRole(roleId);
        return ApiResponse.ok(role.getPermissions());
    }

    // ── Tumblebug Namespace 배정 (자원 탐색기 가상머신 가지·알람 정책의 역할 범위) ──

    @Operation(summary = "역할에 배정된 Namespace 목록",
            description = "role_namespaces 의 배정 목록(nsId 순). name 은 Tumblebug 표시 이름 — Tumblebug 에 없거나 조회에 실패하면 null. 404: 역할 없음")
    @GetMapping("/{roleId}/namespaces")
    public ApiResponse<List<AuthDto.RoleNamespaceResponse>> getNamespaces(@PathVariable Long roleId) {
        return ApiResponse.ok(roleNamespaceService.assigned(roleId));
    }

    @Operation(summary = "Namespace 배정 후보",
            description = "Tumblebug 의 전체 Namespace(nsId 순)와 이 역할의 배정 여부. 404: 역할 없음, 503: Tumblebug 연결 실패")
    @GetMapping("/{roleId}/namespaces/candidates")
    public ApiResponse<List<AuthDto.NamespaceCandidateResponse>> getNamespaceCandidates(@PathVariable Long roleId) {
        return ApiResponse.ok(roleNamespaceService.candidates(roleId));
    }

    @Operation(summary = "Namespace 배정",
            description = "nsIds 를 역할에 배정하고 배정 목록 전체를 돌려준다. 이미 배정된 nsId 는 건너뛴다. "
                    + "400: 비었거나 Tumblebug 에 없는 nsId 포함(하나라도 있으면 아무것도 넣지 않음), 404: 역할 없음, 503: Tumblebug 연결 실패")
    @PortalLog(screen = "역할/권한", action = "Namespace 배정",
               content = "'Namespace 배정: 역할 id ' + #roleId + ', ' + #request.nsIds",
               refType = "ROLE", refId = "#roleId")
    @PostMapping("/{roleId}/namespaces")
    public ApiResponse<List<AuthDto.RoleNamespaceResponse>> assignNamespaces(@PathVariable Long roleId,
                                                                             @RequestBody AuthDto.AssignNamespacesRequest request) {
        return ApiResponse.ok(roleNamespaceService.assign(roleId, request.nsIds()));
    }

    @Operation(summary = "Namespace 배정 해제", description = "404: 역할 없음 또는 배정되지 않은 nsId")
    @PortalLog(screen = "역할/권한", action = "Namespace 배정 해제",
               content = "'Namespace 배정 해제: 역할 id ' + #roleId + ', ' + #nsId",
               refType = "ROLE", refId = "#roleId")
    @DeleteMapping("/{roleId}/namespaces/{nsId}")
    public ApiResponse<Void> unassignNamespace(@PathVariable Long roleId, @PathVariable String nsId) {
        roleNamespaceService.unassign(roleId, nsId);
        return ApiResponse.ok(null);
    }

    private Role findRole(Long id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "역할을 찾을 수 없습니다"));
    }

    private AuthDto.RoleResponse toResponse(Role role) {
        List<String> perms = role.getPermissions().stream()
                .map(p -> p.getResource() + ":" + p.getAction())
                .toList();
        return new AuthDto.RoleResponse(role.getId(), role.getName(), role.getDescription(), perms);
    }
}

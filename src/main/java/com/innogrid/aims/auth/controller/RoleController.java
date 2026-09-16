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
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 역할/권한 관리 API.
 * [요구사항 #7] 역할 관리
 * [요구사항 #8] 자원 권한 조회
 *
 * 포털 로그(docs/08 §10.1 묶음 2): 역할 생성·수정·삭제·권한 추가는 {@code @PortalLog(AUDIT)} 로 남긴다(ref = ROLE). 조회는 남기지 않는다.
 */
@Tag(name = "역할/권한", description = "역할 CRUD, 권한 관리")
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    public RoleController(RoleRepository roleRepository, PermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

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
        roleRepository.deleteById(roleId);
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

    @GetMapping("/{roleId}/permissions")
    public ApiResponse<?> getPermissions(@PathVariable Long roleId) {
        Role role = findRole(roleId);
        return ApiResponse.ok(role.getPermissions());
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

package com.innogrid.aims.auth.controller;

import java.util.List;

import com.innogrid.aims.auth.dto.AuthDto;
import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.auth.repository.UserRepository;
import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * 사용자 관리 API.
 * [요구사항 #3] 사용자 등록 신청
 * [요구사항 #4] 관리자 사용자 추가
 * [요구사항 #5] 사용자 관리
 *
 * 포털 로그(docs/08 §10.1 묶음 2): 생성·수정·삭제·상태 변경·역할 부여/해제는 {@code @PortalLog(AUDIT)}, 목록·상세 조회는 {@code ACCESS} 로 남긴다(ref = USER).
 * 조회는 남기지 않고, 비밀번호는 내용에 넣지 않는다.
 */
@Tag(name = "사용자 관리", description = "사용자 CRUD, 역할 할당, 상태 변경")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository, RoleRepository roleRepository,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PortalLog(type = PortalLogType.ACCESS, screen = "사용자 관리", action = "조회",
               content = "'사용자 목록 조회'")
    @GetMapping
    public ApiResponse<List<AuthDto.UserResponse>> listUsers() {
        List<AuthDto.UserResponse> users = userRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
        return ApiResponse.ok(users);
    }

    @PortalLog(type = PortalLogType.ACCESS, screen = "사용자 관리", action = "조회",
               content = "'사용자 조회: id ' + #p0",
               refType = "USER", refId = "#p0")
    @GetMapping("/{userId}")
    public ApiResponse<AuthDto.UserResponse> getUser(@PathVariable Long userId) {
        User user = findUser(userId);
        return ApiResponse.ok(toResponse(user));
    }

    @PortalLog(screen = "사용자 관리", action = "생성", content = "'사용자 생성: ' + #request.email",
               refType = "USER", refId = "#result.data.id")
    @PostMapping
    public ApiResponse<AuthDto.UserResponse> createUser(@RequestBody AuthDto.CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 등록된 이메일입니다");
        }
        User user = new User(request.email(), passwordEncoder.encode(request.password()),
                request.name(), request.organization());
        userRepository.save(user);
        return ApiResponse.ok(toResponse(user));
    }

    @PortalLog(screen = "사용자 관리", action = "수정", content = "'사용자 수정: id ' + #p0",
               refType = "USER", refId = "#p0")
    @PutMapping("/{userId}")
    public ApiResponse<AuthDto.UserResponse> updateUser(@PathVariable Long userId,
                                                        @RequestBody AuthDto.UpdateUserRequest request) {
        User user = findUser(userId);
        if (request.name() != null) user.setName(request.name());
        if (request.organization() != null) user.setOrganization(request.organization());
        userRepository.save(user);
        return ApiResponse.ok(toResponse(user));
    }

    @PortalLog(screen = "사용자 관리", action = "삭제", content = "'사용자 삭제: id ' + #p0",
               refType = "USER", refId = "#p0")
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> deleteUser(@PathVariable Long userId) {
        userRepository.deleteById(userId);
        return ApiResponse.ok(null);
    }

    @PortalLog(screen = "사용자 관리", action = "상태 변경",
               content = "'사용자 상태 변경: id ' + #p0 + ' → ' + #status",
               refType = "USER", refId = "#p0")
    @PatchMapping("/{userId}/status")
    public ApiResponse<Void> changeStatus(@PathVariable Long userId, @RequestParam String status) {
        User user = findUser(userId);
        user.setStatus(User.UserStatus.valueOf(status.toUpperCase()));
        userRepository.save(user);
        return ApiResponse.ok(null);
    }

    @PortalLog(screen = "사용자 관리", action = "역할 부여",
               content = "'역할 부여: 사용자 id ' + #p0 + ', 역할 id ' + #roleId",
               refType = "USER", refId = "#p0")
    @PostMapping("/{userId}/roles/{roleId}")
    public ApiResponse<Void> assignRole(@PathVariable Long userId, @PathVariable Long roleId) {
        User user = findUser(userId);
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "역할을 찾을 수 없습니다"));
        user.addRole(role);
        userRepository.save(user);
        return ApiResponse.ok(null);
    }

    @PortalLog(screen = "사용자 관리", action = "역할 해제",
               content = "'역할 해제: 사용자 id ' + #p0 + ', 역할 id ' + #roleId",
               refType = "USER", refId = "#p0")
    @DeleteMapping("/{userId}/roles/{roleId}")
    public ApiResponse<Void> removeRole(@PathVariable Long userId, @PathVariable Long roleId) {
        User user = findUser(userId);
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "역할을 찾을 수 없습니다"));
        user.removeRole(role);
        userRepository.save(user);
        return ApiResponse.ok(null);
    }

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"));
    }

    private AuthDto.UserResponse toResponse(User user) {
        List<String> roles = user.getRoles().stream().map(Role::getName).toList();
        return new AuthDto.UserResponse(user.getId(), user.getEmail(), user.getName(),
                user.getOrganization(), user.getStatus().name(), roles,
                user.getCreatedAt());
    }
}

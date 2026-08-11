package com.innogrid.aims.auth.controller;

import java.util.List;

import com.innogrid.aims.auth.dto.AuthDto;
import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.auth.repository.UserRepository;
import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.exception.BusinessException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * 사용자 관리 API.
 * [요구사항 #3] 사용자 등록 신청
 * [요구사항 #4] 관리자 사용자 추가
 * [요구사항 #5] 사용자 관리
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

    @GetMapping
    public ApiResponse<List<AuthDto.UserResponse>> listUsers() {
        List<AuthDto.UserResponse> users = userRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
        return ApiResponse.ok(users);
    }

    @GetMapping("/{userId}")
    public ApiResponse<AuthDto.UserResponse> getUser(@PathVariable Long userId) {
        User user = findUser(userId);
        return ApiResponse.ok(toResponse(user));
    }

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

    @PutMapping("/{userId}")
    public ApiResponse<AuthDto.UserResponse> updateUser(@PathVariable Long userId,
                                                        @RequestBody AuthDto.UpdateUserRequest request) {
        User user = findUser(userId);
        if (request.name() != null) user.setName(request.name());
        if (request.organization() != null) user.setOrganization(request.organization());
        userRepository.save(user);
        return ApiResponse.ok(toResponse(user));
    }

    @DeleteMapping("/{userId}")
    public ApiResponse<Void> deleteUser(@PathVariable Long userId) {
        userRepository.deleteById(userId);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/{userId}/status")
    public ApiResponse<Void> changeStatus(@PathVariable Long userId, @RequestParam String status) {
        User user = findUser(userId);
        user.setStatus(User.UserStatus.valueOf(status.toUpperCase()));
        userRepository.save(user);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{userId}/roles/{roleId}")
    public ApiResponse<Void> assignRole(@PathVariable Long userId, @PathVariable Long roleId) {
        User user = findUser(userId);
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "역할을 찾을 수 없습니다"));
        user.addRole(role);
        userRepository.save(user);
        return ApiResponse.ok(null);
    }

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

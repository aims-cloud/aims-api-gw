package com.innogrid.aims.auth.controller;

import com.innogrid.aims.auth.dto.AuthDto;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API.
 * [요구사항 #1] 로그인 — MDC 통합 관리 포탈 인증
 * [요구사항 #2] 비밀번호 초기화
 */
@Tag(name = "인증", description = "로그인, 토큰 발급/검증/갱신, 회원가입")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 로그인 → Access + Refresh Token 발급 */
    @PostMapping("/login")
    public ApiResponse<?> login(@RequestBody AuthDto.LoginRequest request) {
        return ApiResponse.ok(authService.login(request.email(), request.password()));
    }

    /** 회원가입 */
    @PostMapping("/register")
    public ApiResponse<AuthDto.UserSummaryResponse> register(@RequestBody AuthDto.RegisterRequest request) {
        var user = authService.register(
                request.email(), request.password(), request.name(), request.organization());
        return ApiResponse.ok(new AuthDto.UserSummaryResponse(user.getId(), user.getEmail(), user.getName()));
    }

    /** 토큰 검증 (세부1,3 연동용) — 토큰이 유효하면 사용자 정보 반환 */
    @PostMapping("/validate")
    public ApiResponse<?> validateToken(@RequestBody AuthDto.TokenRequest request) {
        return ApiResponse.ok(authService.validateToken(request.token()));
    }

    /** Access Token 갱신 */
    @PostMapping("/refresh")
    public ApiResponse<?> refreshToken(@RequestBody AuthDto.TokenRequest request) {
        return ApiResponse.ok(authService.refreshToken(request.token()));
    }

    /** API Key 검증 (서비스 간 통신용) */
    @PostMapping("/validate-key")
    public ApiResponse<AuthDto.ApiKeyValidationResponse> validateApiKey(@RequestBody AuthDto.ApiKeyRequest request) {
        boolean valid = authService.validateApiKey(request.apiKey());
        return ApiResponse.ok(new AuthDto.ApiKeyValidationResponse(valid));
    }
}

package com.innogrid.aims.auth.controller;

import com.innogrid.aims.auth.dto.AuthDto;
import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.common.log.PortalLog;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API.
 * [요구사항 #1] 로그인 — MDC 통합 관리 포탈 인증
 * [요구사항 #2] 비밀번호 초기화
 *
 * 포털 로그(docs/08 §10.1 묶음 1): 로그인·가입·토큰 갱신은 {@code @PortalLog(AUTH)} 로 남긴다.
 * 인증 주체가 없는 요청이라 로그인·가입은 요청 이메일을 사용자로 기록하고, 비밀번호·토큰 값은 내용에 넣지 않는다.
 * validate·validate-key 는 세부1·3·서비스 간 호출이라 기록하지 않는다.
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
    @PortalLog(type = PortalLogType.AUTH, screen = "로그인", action = "로그인",
               content = "'로그인: ' + #request.email", userId = "#request.email")
    @PostMapping("/login")
    public ApiResponse<?> login(@RequestBody AuthDto.LoginRequest request) {
        return ApiResponse.ok(authService.login(request.email(), request.password()));
    }

    /** 회원가입 */
    @PortalLog(type = PortalLogType.AUTH, screen = "회원 가입", action = "가입",
               content = "'회원 가입: ' + #request.email", userId = "#request.email",
               refType = "USER", refId = "#result.data.id")
    @PostMapping("/register")
    public ApiResponse<AuthDto.UserSummaryResponse> register(@RequestBody AuthDto.RegisterRequest request) {
        User user = authService.register(
                request.email(), request.password(), request.name(), request.organization());
        return ApiResponse.ok(new AuthDto.UserSummaryResponse(user.getId(), user.getEmail(), user.getName()));
    }

    /** 토큰 검증 (세부1,3 연동용) — 토큰이 유효하면 사용자 정보 반환 */
    @PostMapping("/validate")
    public ApiResponse<?> validateToken(@RequestBody AuthDto.TokenRequest request) {
        return ApiResponse.ok(authService.validateToken(request.token()));
    }

    /** Access Token 갱신 */
    @PortalLog(type = PortalLogType.AUTH, screen = "로그인", action = "토큰 갱신", content = "'토큰 갱신'")
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

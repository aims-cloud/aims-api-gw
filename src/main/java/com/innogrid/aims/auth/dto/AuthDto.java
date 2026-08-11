package com.innogrid.aims.auth.dto;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public class AuthDto {

    // ── 요청 ────────────────────────────────────────────────────

    @Schema(description = "로그인 요청")
    public record LoginRequest(
            @Schema(description = "이메일", example = "admin@aims.cloud", requiredMode = Schema.RequiredMode.REQUIRED)
            String email,
            @Schema(description = "비밀번호", example = "password123!", requiredMode = Schema.RequiredMode.REQUIRED)
            String password
    ) {}

    @Schema(description = "회원가입 요청")
    public record RegisterRequest(
            @Schema(description = "이메일", example = "user@innogrid.com", requiredMode = Schema.RequiredMode.REQUIRED)
            String email,
            @Schema(description = "비밀번호 (8자 이상)", example = "password123!", requiredMode = Schema.RequiredMode.REQUIRED)
            String password,
            @Schema(description = "이름", example = "김승한", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "소속 기관", example = "이노그리드")
            String organization
    ) {}

    @Schema(description = "토큰 요청 (검증/갱신)")
    public record TokenRequest(
            @Schema(description = "JWT 토큰", example = "eyJhbGciOiJIUzI1NiJ9...", requiredMode = Schema.RequiredMode.REQUIRED)
            String token
    ) {}

    @Schema(description = "API Key 검증 요청")
    public record ApiKeyRequest(
            @Schema(description = "API Key 값", example = "a1b2c3d4e5f6...", requiredMode = Schema.RequiredMode.REQUIRED)
            String apiKey
    ) {}

    @Schema(description = "사용자 생성 요청 (관리자)")
    public record CreateUserRequest(
            @Schema(description = "이메일", example = "newuser@sub3.org", requiredMode = Schema.RequiredMode.REQUIRED)
            String email,
            @Schema(description = "초기 비밀번호", example = "temp1234!", requiredMode = Schema.RequiredMode.REQUIRED)
            String password,
            @Schema(description = "이름", example = "홍길동", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "소속 기관", example = "세부3 기관")
            String organization
    ) {}

    @Schema(description = "사용자 정보 수정 요청")
    public record UpdateUserRequest(
            @Schema(description = "이름", example = "홍길동")
            String name,
            @Schema(description = "소속 기관", example = "이노그리드")
            String organization
    ) {}

    @Schema(description = "역할 생성/수정 요청")
    public record CreateRoleRequest(
            @Schema(description = "역할명", example = "OPERATOR", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "설명", example = "MDC 운영자")
            String description
    ) {}

    @Schema(description = "권한 추가 요청")
    public record CreatePermissionRequest(
            @Schema(description = "대상 리소스", example = "vm", requiredMode = Schema.RequiredMode.REQUIRED)
            String resource,
            @Schema(description = "허용 액션", example = "create", requiredMode = Schema.RequiredMode.REQUIRED)
            String action,
            @Schema(description = "설명", example = "VM 생성 권한")
            String description
    ) {}

    // ── 응답 ────────────────────────────────────────────────────

    @Schema(description = "로그인 응답 (토큰 발급)")
    public record TokenResponse(
            @Schema(description = "Access Token (30분)", example = "eyJhbGciOiJIUzI1NiJ9...")
            String accessToken,
            @Schema(description = "Refresh Token (7일)", example = "eyJhbGciOiJIUzI1NiJ9...")
            String refreshToken
    ) {}

    @Schema(description = "Access Token 갱신 응답")
    public record AccessTokenResponse(
            @Schema(description = "새로운 Access Token", example = "eyJhbGciOiJIUzI1NiJ9...")
            String accessToken
    ) {}

    @Schema(description = "토큰 검증 응답 (세부1,3 연동용)")
    public record TokenValidationResponse(
            @Schema(description = "유효 여부", example = "true")
            boolean valid,
            @Schema(description = "이메일", example = "user@innogrid.com")
            String email,
            @Schema(description = "사용자 ID", example = "1")
            Object userId,
            @Schema(description = "이름", example = "김승한")
            String name,
            @Schema(description = "역할 목록 (쉼표 구분)", example = "ADMIN,USER")
            String roles,
            @Schema(description = "소속 기관", example = "이노그리드")
            String organization
    ) {}

    @Schema(description = "API Key 검증 응답")
    public record ApiKeyValidationResponse(
            @Schema(description = "유효 여부", example = "true")
            boolean valid
    ) {}

    @Schema(description = "사용자 정보 응답")
    public record UserResponse(
            @Schema(description = "사용자 ID", example = "1")
            Long id,
            @Schema(description = "이메일", example = "admin@aims.cloud")
            String email,
            @Schema(description = "이름", example = "관리자")
            String name,
            @Schema(description = "소속 기관", example = "이노그리드")
            String organization,
            @Schema(description = "상태", example = "ACTIVE", allowableValues = {"ACTIVE", "INACTIVE", "PENDING"})
            String status,
            @Schema(description = "역할 목록", example = "[\"ADMIN\", \"USER\"]")
            List<String> roles,
            @Schema(description = "생성일시")
            OffsetDateTime createdAt
    ) {}

    @Schema(description = "사용자 요약 응답 (회원가입 시)")
    public record UserSummaryResponse(
            @Schema(description = "사용자 ID", example = "1")
            Long id,
            @Schema(description = "이메일", example = "user@innogrid.com")
            String email,
            @Schema(description = "이름", example = "김승한")
            String name
    ) {}

    @Schema(description = "역할 정보 응답")
    public record RoleResponse(
            @Schema(description = "역할 ID", example = "1")
            Long id,
            @Schema(description = "역할명", example = "ADMIN")
            String name,
            @Schema(description = "설명", example = "시스템 관리자")
            String description,
            @Schema(description = "권한 목록 (리소스:액션)", example = "[\"vm:create\", \"vm:delete\"]")
            List<String> permissions
    ) {}

    @Schema(description = "권한 정보 응답")
    public record PermissionResponse(
            @Schema(description = "권한 ID", example = "1")
            Long id,
            @Schema(description = "대상 리소스", example = "vm")
            String resource,
            @Schema(description = "허용 액션", example = "create")
            String action,
            @Schema(description = "설명", example = "VM 생성 권한")
            String description
    ) {}

    private AuthDto() {}
}

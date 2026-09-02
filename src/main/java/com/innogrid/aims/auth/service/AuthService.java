package com.innogrid.aims.auth.service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.auth.entity.ApiKey;
import com.innogrid.aims.auth.entity.Role;
import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.repository.ApiKeyRepository;
import com.innogrid.aims.auth.repository.RoleRepository;
import com.innogrid.aims.auth.repository.UserRepository;
import com.innogrid.aims.auth.security.JwtProvider;
import com.innogrid.aims.common.exception.BusinessException;

import io.jsonwebtoken.Claims;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    public AuthService(UserRepository userRepository, RoleRepository roleRepository,
                       ApiKeyRepository apiKeyRepository, PasswordEncoder passwordEncoder,
                       JwtProvider jwtProvider) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
    }

    @Transactional
    public Map<String, String> login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다");
        }

        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "비활성화된 계정입니다");
        }

        return Map.of(
                "accessToken", jwtProvider.generateAccessToken(user),
                "refreshToken", jwtProvider.generateRefreshToken(user)
        );
    }

    @Transactional
    public User register(String email, String password, String name, String organization) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 등록된 이메일입니다");
        }

        User user = new User(email, passwordEncoder.encode(password), name, organization);

        // 기본 역할(USER) 부여
        roleRepository.findByName("USER").ifPresent(user::addRole);

        return userRepository.save(user);
    }

    public Map<String, Object> validateToken(String token) {
        if (!jwtProvider.validateToken(token)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다");
        }
        Claims claims = jwtProvider.parseClaims(token);
        return Map.of(
                "valid", true,
                "email", claims.getSubject(),
                "userId", claims.get("userId"),
                "name", claims.get("name"),
                "roles", claims.get("roles"),
                "organization", claims.get("organization")
        );
    }

    public Map<String, String> refreshToken(String refreshToken) {
        if (!jwtProvider.validateToken(refreshToken)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다");
        }
        String email = jwtProvider.getEmailFromToken(refreshToken);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"));

        return Map.of("accessToken", jwtProvider.generateAccessToken(user));
    }

    public boolean validateApiKey(String key) {
        return apiKeyRepository.findByKeyValue(key)
                .map(ApiKey::isValid)
                .orElse(false);
    }

    public Set<String> currentUserRoleIds() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                roles.add(name.substring("ROLE_".length()));
            }
        }
        return roles;
    }
}

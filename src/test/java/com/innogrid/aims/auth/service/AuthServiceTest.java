package com.innogrid.aims.auth.service;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.common.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class AuthServiceTest {

    @Autowired
    AuthService authService;

    @Test
    void 회원가입_성공() {
        User user = authService.register("test@test.com", "pass1234!", "테스트", "테스트기관");
        assertNotNull(user.getId());
        assertEquals("test@test.com", user.getEmail());
        assertFalse(user.getRoles().isEmpty());
    }

    @Test
    void 중복_이메일_회원가입_실패() {
        authService.register("dup@test.com", "pass1234!", "중복", "기관");
        assertThrows(BusinessException.class, () ->
                authService.register("dup@test.com", "pass1234!", "중복2", "기관"));
    }

    @Test
    void 로그인_성공_토큰_발급() {
        authService.register("login@test.com", "pass1234!", "로그인", "기관");
        Map<String, String> tokens = authService.login("login@test.com", "pass1234!");
        assertNotNull(tokens.get("accessToken"));
        assertNotNull(tokens.get("refreshToken"));
    }

    @Test
    void 잘못된_비밀번호_로그인_실패() {
        authService.register("fail@test.com", "pass1234!", "실패", "기관");
        assertThrows(BusinessException.class, () ->
                authService.login("fail@test.com", "wrongpass"));
    }

    @Test
    void 토큰_검증() {
        authService.register("verify@test.com", "pass1234!", "검증", "기관");
        Map<String, String> tokens = authService.login("verify@test.com", "pass1234!");
        Map<String, Object> result = authService.validateToken(tokens.get("accessToken"));
        assertTrue((Boolean) result.get("valid"));
        assertEquals("verify@test.com", result.get("email"));
    }

    @Test
    void 토큰_갱신() {
        authService.register("refresh@test.com", "pass1234!", "갱신", "기관");
        Map<String, String> tokens = authService.login("refresh@test.com", "pass1234!");
        Map<String, String> newTokens = authService.refreshToken(tokens.get("refreshToken"));
        assertNotNull(newTokens.get("accessToken"));
    }
}

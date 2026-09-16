package com.innogrid.aims.auth.controller;

import java.util.List;
import java.util.Map;

import com.innogrid.aims.auth.entity.User;
import com.innogrid.aims.auth.service.AuthService;
import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증 API 포털 로그(AUTH) — 인증 주체가 없는 로그인·가입 요청이 요청 이메일을 사용자로 남기는지,
 * 로그인 실패가 FAILURE 와 사유로 남는지, 비밀번호·토큰이 내용에 들어가지 않는지 MockMvc 로 본다.
 * 인증 서비스는 대역. 로그는 REQUIRES_NEW 로 커밋되므로 테스트 트랜잭션을 쓰지 않고 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthPortalLogTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    PortalLogRepository repository;
    @MockitoBean
    AuthService authService;

    @BeforeEach
    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void 로그인_성공은_AUTH_SUCCESS로_요청_이메일을_사용자로_남기고_비밀번호는_남기지_않는다() throws Exception {
        when(authService.login("admin@innogrid.com", "secret!1")).thenReturn(Map.of("accessToken", "a", "refreshToken", "r"));

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@innogrid.com\",\"password\":\"secret!1\"}")
                        .header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        PortalLogEntry log = logs.get(0);
        assertThat(log.getType()).isEqualTo(PortalLogType.AUTH);
        assertThat(log.getResult()).isEqualTo(PortalLogResult.SUCCESS);
        assertThat(log.getScreen()).isEqualTo("로그인");
        assertThat(log.getAction()).isEqualTo("로그인");
        assertThat(log.getContent()).isEqualTo("로그인: admin@innogrid.com");
        assertThat(log.getUserId()).isEqualTo("admin@innogrid.com");
        assertThat(log.getUserName()).isEqualTo("admin@innogrid.com");            // users 테이블에 없으면 ID 로 대체
        assertThat(log.getClientIp()).isEqualTo("203.0.113.7");
        assertThat(log.getRequestUri()).isEqualTo("/api/v1/auth/login");
        assertThat(log.getContent()).doesNotContain("secret");
    }

    @Test
    void 로그인_실패는_FAILURE와_사유로_남고_시도한_이메일이_사용자다() throws Exception {
        when(authService.login(anyString(), anyString()))
                .thenThrow(new BusinessException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"));

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"guest@innogrid.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(logs.get(0).getUserId()).isEqualTo("guest@innogrid.com");
        assertThat(logs.get(0).getDescription()).isEqualTo("이메일 또는 비밀번호가 올바르지 않습니다");
        assertThat(logs.get(0).getContent()).isEqualTo("로그인: guest@innogrid.com");
    }

    @Test
    void 가입은_요청_이메일을_사용자로_남기고_생성된_사용자_id를_ref로_건다() throws Exception {
        User created = new User("new@innogrid.com", "encoded", "신규", "이노그리드");
        ReflectionTestUtils.setField(created, "id", 42L);
        when(authService.register("new@innogrid.com", "pw12345!", "신규", "이노그리드")).thenReturn(created);

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@innogrid.com\",\"password\":\"pw12345!\",\"name\":\"신규\",\"organization\":\"이노그리드\"}"))
                .andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        PortalLogEntry log = logs.get(0);
        assertThat(log.getType()).isEqualTo(PortalLogType.AUTH);
        assertThat(log.getScreen()).isEqualTo("회원 가입");
        assertThat(log.getAction()).isEqualTo("가입");
        assertThat(log.getUserId()).isEqualTo("new@innogrid.com");
        assertThat(log.getContent()).isEqualTo("회원 가입: new@innogrid.com");
        assertThat(log.getRefType()).isEqualTo("USER");
        assertThat(log.getRefId()).isEqualTo("42");
    }

    @Test
    void 토큰_갱신은_토큰_값_없이_고정_문구로_남고_사용자는_비어_있다() throws Exception {
        when(authService.refreshToken("refresh-token-value")).thenReturn(Map.of("accessToken", "new"));

        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"refresh-token-value\"}"))
                .andExpect(status().isOk());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAction()).isEqualTo("토큰 갱신");
        assertThat(logs.get(0).getContent()).isEqualTo("토큰 갱신");
        assertThat(logs.get(0).getContent()).doesNotContain("refresh-token-value");
        assertThat(logs.get(0).getUserId()).isNull();
    }

    @Test
    void 본문이_깨진_로그인은_폴백_인터셉터가_검증_실패로_남긴다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().is4xxClientError());

        List<PortalLogEntry> logs = repository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getType()).isEqualTo(PortalLogType.AUTH);
        assertThat(logs.get(0).getResult()).isEqualTo(PortalLogResult.FAILURE);
        assertThat(logs.get(0).getContent()).isEqualTo("로그인 로그인 요청 검증 실패");
    }
}

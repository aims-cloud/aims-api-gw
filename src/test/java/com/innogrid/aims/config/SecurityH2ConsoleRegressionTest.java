package com.innogrid.aims.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H2 콘솔 보안 매처가 <b>콘솔이 꺼진 환경</b>에서 다른 요청을 깨뜨리지 않는가.
 *
 * <p>테스트 프로파일은 운영과 같이 {@code spring.h2.console.enabled} 가 없어 콘솔이 꺼져 있다. 2026-09-02 에
 * 매처를 {@code PathRequest.toH2Console()} 로 바꿨을 때, 그 매처가 첫 요청에서 {@code H2ConsoleProperties} 빈을
 * 찾다가 {@code NoSuchBeanDefinitionException} 을 던져 <b>모든 요청</b>이 보안 필터에서 실패했다(MockMvc 50건).
 * 이 클래스는 그 조건을 그대로 두고 세 가지를 고정한다 — 인증 요청은 200, 콘솔 경로는 404(서블릿 없음, 500·403 아님),
 * 미인증은 여전히 403.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityH2ConsoleRegressionTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @WithMockUser
    void 콘솔이_꺼진_환경에서_인증된_API_요청은_정상_200_이다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/engines"))
                .andExpect(status().isOk());
    }

    @Test
    void 콘솔_경로는_서블릿이_없어_404_다_500_이나_403_이_아니다() throws Exception {
        // permitAll 이라 인증 없이 도달하고, 콘솔 서블릿이 없어 404. 매처가 빈을 찾지 않으므로 예외도 없다.
        mockMvc.perform(get("/h2-console").with(anonymous()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 미인증_API_요청은_여전히_403_이다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/engines").with(anonymous()))
                .andExpect(status().isForbidden());
    }
}

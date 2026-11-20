package com.innogrid.aims.scheduler.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 요청 본문을 읽지 못했을 때 스케줄러 API가 <b>표준 오류 응답으로 400</b>을 돌려주는가.
 *
 * <p>처리기가 없으면 스프링 기본 처리가 {@code sendError}로 빈 본문을 보내고, 실제 서블릿
 * 컨테이너에서는 {@code /error} 재요청이 인증에 막혀 403이 된다. MockMvc는 재요청을 하지
 * 않아 상태는 400으로 보이지만 <b>본문이 비어</b> 있다 — 그래서 여기서는 상태와 함께
 * {@code success=false}와 사유 문구까지 확인한다. 이 본문이 있어야 처리기를 탄 것이다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class SchedulerRequestBodyErrorTest {

    @Autowired
    MockMvc mockMvc;

    @DisplayName("깨진 JSON 은 400 과 사유를 돌려준다")
    @Test
    void broken_json_400() throws Exception {
        mockMvc.perform(post("/api/v1/scheduler/workloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"broken\", "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("요청 본문을 읽을 수 없습니다")));
    }

    @DisplayName("빈 본문은 400 과 사유를 돌려준다")
    @Test
    void empty_body_400() throws Exception {
        mockMvc.perform(put("/api/v1/scheduler/policies/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("요청 본문을 읽을 수 없습니다")));
    }

    @DisplayName("JSON 이 아닌 본문은 400 과 사유를 돌려준다")
    @Test
    void non_json_body_400() throws Exception {
        mockMvc.perform(post("/api/v1/scheduler/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("name=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}

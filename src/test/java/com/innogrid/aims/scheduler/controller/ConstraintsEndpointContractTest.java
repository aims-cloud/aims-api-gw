package com.innogrid.aims.scheduler.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /scheduler/constraints} 계약 — C0~C6 일곱 항목, 평가 순서, C5 는 정책에서 켜고 끌 수 있고 C6 은 원장에만 달림.
 * 링크 기능을 더할 때 게이트와 무관하게 바뀐 유일한 응답이라 여기서 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ConstraintsEndpointContractTest {

    @Autowired
    MockMvc mockMvc;

    @DisplayName("제약 설명은 C0 부터 C6 까지 일곱 항목이다")
    @Test
    void constraint_list_7_items() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/constraints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data[0].code").value("C0"))
                .andExpect(jsonPath("$.data[4].code").value("C4"))
                .andExpect(jsonPath("$.data[5].code").value("C5"))
                .andExpect(jsonPath("$.data[5].name").value("MDC 링크"))
                .andExpect(jsonPath("$.data[5].togglable").value(true))
                .andExpect(jsonPath("$.data[5].source").value(containsString("affinityEnabled")))
                .andExpect(jsonPath("$.data[5].description").value(containsString("DOWN")))
                .andExpect(jsonPath("$.data[6].code").value("C6"))
                .andExpect(jsonPath("$.data[6].name").value("MDC 전력 예산"))
                .andExpect(jsonPath("$.data[6].togglable").value(false))
                .andExpect(jsonPath("$.data[6].source").value(containsString("mdc-power-budgets")))
                .andExpect(jsonPath("$.data[6].description").value(containsString("실효 전력")));
    }
}

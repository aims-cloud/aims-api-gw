package com.innogrid.aims.scheduler.controller;

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
 * {@code GET /scheduler/constraints} 계약 — C0~C5 여섯 항목, 평가 순서, C5 는 정책에서 켜고 끌 수 있음.
 * 4-② 에서 게이트와 무관하게 바뀌는 유일한 응답이라 여기서 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ConstraintsEndpointContractTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 제약_설명은_C0_부터_C5_까지_여섯_항목이다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/constraints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].code").value("C0"))
                .andExpect(jsonPath("$.data[4].code").value("C4"))
                .andExpect(jsonPath("$.data[5].code").value("C5"))
                .andExpect(jsonPath("$.data[5].name").value("MDC 링크"))
                .andExpect(jsonPath("$.data[5].togglable").value(true))
                .andExpect(jsonPath("$.data[5].source").value(containsString("affinityEnabled")))
                .andExpect(jsonPath("$.data[5].description").value(containsString("DOWN")));
    }
}

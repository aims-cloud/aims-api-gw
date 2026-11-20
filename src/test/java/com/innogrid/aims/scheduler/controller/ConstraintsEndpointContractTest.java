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
 * {@code GET /scheduler/constraints} 응답 — C0~C7 여덟 항목, 평가 순서(C7 은 C0 다음), C5는 정책에서 켜고 끌 수 있고
 * C6은 원장에만 달림. 링크 기능을 더할 때 게이트와 무관하게 바뀐 유일한 응답이라 여기서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ConstraintsEndpointContractTest {

    @Autowired
    MockMvc mockMvc;

    @DisplayName("제약 설명은 C0 부터 C7 까지 여덟 항목이고 C7 은 C0 다음 자리다")
    @Test
    void constraint_list_8_items() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/constraints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[0].code").value("C0"))
                .andExpect(jsonPath("$.data[1].code").value("C7"))
                .andExpect(jsonPath("$.data[1].name").value("노드 배치 중지"))
                .andExpect(jsonPath("$.data[1].togglable").value(false))
                .andExpect(jsonPath("$.data[1].source").value(containsString("node-placement-holds")))
                .andExpect(jsonPath("$.data[1].description").value(containsString("C1 보다 앞")))
                .andExpect(jsonPath("$.data[2].code").value("C1"))
                .andExpect(jsonPath("$.data[5].code").value("C4"))
                .andExpect(jsonPath("$.data[6].code").value("C5"))
                .andExpect(jsonPath("$.data[6].name").value("MDC 링크"))
                .andExpect(jsonPath("$.data[6].togglable").value(true))
                .andExpect(jsonPath("$.data[6].source").value(containsString("affinityEnabled")))
                .andExpect(jsonPath("$.data[6].description").value(containsString("DOWN")))
                .andExpect(jsonPath("$.data[7].code").value("C6"))
                .andExpect(jsonPath("$.data[7].name").value("MDC 전력 예산"))
                .andExpect(jsonPath("$.data[7].togglable").value(false))
                .andExpect(jsonPath("$.data[7].source").value(containsString("mdc-power-budgets")))
                .andExpect(jsonPath("$.data[7].description").value(containsString("실효 전력")));
    }

    @DisplayName("C3 설명은 냉각 대기가 같거나 더 낮은 임계치의 평가에서만 노드를 제외한다고 적는다")
    @Test
    void c3_description_says_cooldown_only_for_same_or_lower_limit() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/constraints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[4].code").value("C3"))
                .andExpect(jsonPath("$.data[4].description").value(containsString("같거나 더 낮은 임계치")));
    }

    @DisplayName("C1 설명은 GPU 를 워크로드가 요구할 때만 본다고 적는다")
    @Test
    void c1_description_says_gpu_checked_only_when_required() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/constraints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].code").value("C1"))
                .andExpect(jsonPath("$.data[2].description").value(containsString("요구할 때만")));
    }
}

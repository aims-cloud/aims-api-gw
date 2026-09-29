package com.innogrid.aims.scheduler.controller;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.config.SchedulerProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /scheduler/workloads/retry-policy} — 설정의 상한·간격을 그대로 돌려주고, 리터럴 경로가
 * {@code /workloads/{workloadId}} 에 잡히지 않는다(200 이 곧 증명).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class WorkloadRetryPolicyApiTest {

    private static final String PATH = "/api/v1/scheduler/workloads/retry-policy";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SchedulerProperties properties;

    @DisplayName("재시도 정책은 설정의 상한·간격 두 값이고 키 순서가 고정이다")
    @Test
    void policy_reflects_properties() throws Exception {
        String body = mockMvc.perform(get(PATH)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode d = objectMapper.readTree(body).get("data");

        List<String> keys = new ArrayList<>();
        d.propertyNames().forEach(keys::add);
        assertEquals(List.of("maxRetryCount", "retryBackoffMinutes"), keys);
        assertEquals(properties.getMaxRetryCount(), d.get("maxRetryCount").asInt());
        assertEquals(properties.getRetryBackoffMinutes(), d.get("retryBackoffMinutes").asInt());
        assertTrue(d.get("maxRetryCount").asInt() >= 1, "상한은 1 이상 — 저장소의 소진 판정(retryCount >= 상한)이 뜻을 갖는다");
    }

    @DisplayName("미인증은 403 이다")
    @Test
    void anonymous_forbidden() throws Exception {
        mockMvc.perform(get(PATH).with(anonymous())).andExpect(status().isForbidden());
    }
}

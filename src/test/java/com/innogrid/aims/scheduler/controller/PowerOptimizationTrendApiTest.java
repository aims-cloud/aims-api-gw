package com.innogrid.aims.scheduler.controller;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.dto.PowerOptimizationTrendResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /scheduler/optimization/power/trend} 테스트 — 응답 6필드·지점 11필드, 저장소 없는 환경에서도 200 + 사유,
 * 구간 검증 400, 미인증 403, 기존 {@code /optimization/power} 불변, Swagger의 선택 파라미터 {@code mdcId}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PowerOptimizationTrendApiTest {

    private static final String PATH = "/api/v1/scheduler/optimization/power/trend";

    /** 추세 응답 6필드. */
    static final Set<String> RESPONSE_FIELDS =
            new TreeSet<>(Set.of("from", "to", "step", "points", "message", "energy"));
    /** 지점 11필드. */
    static final Set<String> POINT_FIELDS = new TreeSet<>(Set.of(
            "at", "totalNodes", "activeNodes", "idleNodes", "totalPowerWatts", "reclaimableIdleWatts",
            "avgPeiRaw", "minPeiRaw", "maxPeiRaw", "evaluableNodes", "measured"));

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @DisplayName("저장소가 없는 환경에서도 200 이고 사유를 담은 빈 결과다")
    @Test
    void no_repository_200_with_reason() throws Exception {
        String body = mockMvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andReturn().getResponse().getContentAsString();
        JsonNode d = objectMapper.readTree(body).get("data");

        TreeSet<String> keys = new TreeSet<>(); d.propertyNames().forEach(keys::add);
        assertEquals(RESPONSE_FIELDS, keys);
        assertEquals("5m", d.get("step").asString());
        assertTrue(d.get("points").isArray() && d.get("points").isEmpty());
        assertFalse(d.get("message").isNull(), "저장소 없음 → 사유");
    }

    @DisplayName("지점 필드 11개는 직렬화 계약으로 고정된다")
    @Test
    void point_11_fields() throws Exception {
        PowerOptimizationTrendResponse.Point p = new PowerOptimizationTrendResponse.Point(
                java.time.OffsetDateTime.now(), 3, 1, 2, 510.0, 300.0, 0.15, 0.0067, 0.4, 3, true);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(
                new PowerOptimizationTrendResponse(null, null, "5m", List.of(p), null, null))).get("points").get(0);
        TreeSet<String> keys = new TreeSet<>(); json.propertyNames().forEach(keys::add);
        assertEquals(POINT_FIELDS, keys);
    }

    @DisplayName("구간이 뒤집히거나 지점이 너무 많으면 400")
    @Test
    void reversed_range_or_too_many_400() throws Exception {
        mockMvc.perform(get(PATH).param("from", "2026-09-03T10:00:00Z").param("to", "2026-09-03T09:00:00Z"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get(PATH).param("step", "1s")).andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH).param("step", "abc")).andExpect(status().isBadRequest());
    }

    @DisplayName("Swagger 의 추세 파라미터에 선택 mdcId 가 있다")
    @Test
    void swagger_has_optional_mdc_id() throws Exception {
        JsonNode params = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("paths").get(PATH).get("get").get("parameters");
        List<String> names = new java.util.ArrayList<>();
        JsonNode mdcId = null;
        for (JsonNode p : params) {
            names.add(p.get("name").asString());
            if ("mdcId".equals(p.get("name").asString())) mdcId = p;
        }
        assertEquals(List.of("from", "to", "step", "mdcId"), names);
        assertNotNull(mdcId);
        assertFalse(mdcId.path("required").asBoolean(false), "mdcId 는 선택");
    }

    @DisplayName("미인증은 403 이고 기존 진단 경로는 그대로 200")
    @Test
    void unauthenticated_403_diagnosis_200() throws Exception {
        mockMvc.perform(get(PATH).with(SecurityMockMvcRequestPostProcessors.anonymous())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/scheduler/optimization/power")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cluster").exists());
    }
}

package com.innogrid.aims.scheduler.controller;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
 * {@code GET /scheduler/optimization/power/trend} 계약 — 응답 5필드·지점 11필드, 저장소 없는 환경에서도 200 + 사유,
 * 구간 검증 400, 미인증 403, 기존 {@code /optimization/power} 불변.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PowerOptimizationTrendApiTest {

    private static final String PATH = "/api/v1/scheduler/optimization/power/trend";

    /** 추세 응답 5필드. */
    static final Set<String> RESPONSE_FIELDS = new TreeSet<>(Set.of("from", "to", "step", "points", "message"));
    /** 지점 11필드. */
    static final Set<String> POINT_FIELDS = new TreeSet<>(Set.of(
            "at", "totalNodes", "activeNodes", "idleNodes", "totalPowerWatts", "reclaimableIdleWatts",
            "avgPeiRaw", "minPeiRaw", "maxPeiRaw", "evaluableNodes", "measured"));

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void 저장소가_없는_환경에서도_200_이고_사유를_담은_빈_결과다() throws Exception {
        String body = mockMvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andReturn().getResponse().getContentAsString();
        JsonNode d = objectMapper.readTree(body).get("data");

        TreeSet<String> keys = new TreeSet<>(); d.propertyNames().forEach(keys::add);
        assertEquals(RESPONSE_FIELDS, keys);
        assertEquals("5m", d.get("step").asString());
        assertTrue(d.get("points").isArray() && d.get("points").isEmpty());
        assertFalse(d.get("message").isNull(), "저장소 없음 → 사유");
    }

    @Test
    void 지점_필드_11개는_직렬화_계약으로_고정된다() throws Exception {
        PowerOptimizationTrendResponse.Point p = new PowerOptimizationTrendResponse.Point(
                java.time.OffsetDateTime.now(), 3, 1, 2, 510.0, 300.0, 0.15, 0.0067, 0.4, 3, true);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(
                new PowerOptimizationTrendResponse(null, null, "5m", List.of(p), null))).get("points").get(0);
        TreeSet<String> keys = new TreeSet<>(); json.propertyNames().forEach(keys::add);
        assertEquals(POINT_FIELDS, keys);
    }

    @Test
    void 구간이_뒤집히거나_지점이_너무_많으면_400() throws Exception {
        mockMvc.perform(get(PATH).param("from", "2026-09-03T10:00:00Z").param("to", "2026-09-03T09:00:00Z"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get(PATH).param("step", "1s")).andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH).param("step", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    void 미인증은_403_이고_기존_진단_경로는_그대로_200() throws Exception {
        mockMvc.perform(get(PATH).with(SecurityMockMvcRequestPostProcessors.anonymous())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/scheduler/optimization/power")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cluster").exists());
    }
}

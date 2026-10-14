package com.innogrid.aims.scheduler.controller;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배치 정책 요청 검증과 부분 수정 의미.
 *
 * <p>최소 검증 — 저장하면 확실히 망가지는 값만 거부한다. 합≠1 은 허용(비율 원칙). 부분 수정은 "키가 있으면
 * 반영, 없으면 유지" 이고, 비울 수 있는 세 필드는 명시적 null 로 비운다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PolicyRequestValidationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // ── 생성 검증 ──────────────────────────────────────────────

    @DisplayName("이름이 없으면 400")
    @Test
    void missing_name_400() throws Exception {
        create("{\"cpuWeight\":0.3}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("정책 이름")));
    }

    @DisplayName("가중치가 음수면 400 점수 부호가 뒤집힌다")
    @Test
    void negative_weight_400() throws Exception {
        create("{\"name\":\"" + name() + "\",\"cpuWeight\":-1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("가중치는 0 이상")));
    }

    @DisplayName("가중치가 전부 0 이면 400")
    @Test
    void all_weights_zero_400() throws Exception {
        create("{\"name\":\"" + name() + "\",\"cpuWeight\":0,\"memoryWeight\":0,\"powerWeight\":0,"
                + "\"temperatureWeight\":0,\"networkWeight\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("가중치 합")));
    }

    @DisplayName("가중치 합이 1 이 아니어도 허용한다 비율 원칙")
    @Test
    void weight_sum_not_1_allowed() throws Exception {
        String body = create("{\"name\":\"" + name() + "\",\"cpuWeight\":25,\"memoryWeight\":25,\"powerWeight\":20,"
                + "\"temperatureWeight\":15,\"networkWeight\":15}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cpuWeight").value(25.0))
                .andReturn().getResponse().getContentAsString();
        cleanup(body);
    }

    @DisplayName("사용률 임계가 0 이하거나 100 초과면 400")
    @Test
    void utilization_threshold_out_of_range_400() throws Exception {
        create("{\"name\":\"" + name() + "\",\"maxCpuUsagePercent\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("maxCpuUsagePercent")));
        create("{\"name\":\"" + name() + "\",\"maxMemoryUsagePercent\":101}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("maxMemoryUsagePercent")));
    }

    @DisplayName("온도 임계가 0 이면 400")
    @Test
    void temperature_threshold_0_400() throws Exception {
        create("{\"name\":\"" + name() + "\",\"maxTemperatureCelsius\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("온도 임계")));
    }

    @DisplayName("모르는 기본 엔진은 400 아는 엔진은 200")
    @Test
    void unknown_engine_400() throws Exception {
        create("{\"name\":\"" + name() + "\",\"defaultEngine\":\"nope\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("알 수 없는 스케줄링 엔진")));
        String body = create("{\"name\":\"" + name() + "\",\"defaultEngine\":\"power\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultEngine").value("power"))
                .andReturn().getResponse().getContentAsString();
        cleanup(body);
    }

    @DisplayName("이름이 중복되면 409 예전에는 DB 예외 500")
    @Test
    void duplicate_name_409() throws Exception {
        String n = name();
        String body = create("{\"name\":\"" + n + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        create("{\"name\":\"" + n + "\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("이미 있는 정책 이름")));
        cleanup(body);
    }

    // ── 부분 수정 의미 ─────────────────────────────────────────

    @DisplayName("부분 수정은 보낸 키만 반영하고 명시적 null 은 비운다")
    @Test
    void patch_null_clears_field() throws Exception {
        // active:false — 이 테스트는 null 의미만 본다. 활성으로 만들면 종류를 공통(null)으로 바꾸는 순간 다른 테스트가
        // 스케줄링 중 저장해 둔 활성 공통 정책(default)과 충돌해 409 가 나 실행 순서에 따라 흔들린다.
        String body = create("{\"name\":\"" + name() + "\",\"resourceType\":\"gpu\",\"defaultEngine\":\"power\","
                + "\"warningTemperatureCelsius\":60,\"cpuWeight\":0.3,\"active\":false}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = idOf(body);

        // 키 없음 → 유지
        Map<String, Object> kept = update(id, "{\"description\":\"desc\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().transform(this::data);
        assertEquals("GPU", kept.get("resourceType"));
        assertEquals("power", kept.get("defaultEngine"));
        assertEquals(60.0, kept.get("warningTemperatureCelsius"));
        assertEquals(0.3, kept.get("cpuWeight"));

        // 명시적 null → 비움 (공통 정책 · 기본 엔진 · 설정 기본값)
        Map<String, Object> cleared = update(id, "{\"resourceType\":null,\"defaultEngine\":null,\"warningTemperatureCelsius\":null}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString().transform(this::data);
        assertNull(cleared.get("resourceType"), "명시적 null 은 공통 정책으로 전환");
        assertNull(cleared.get("defaultEngine"));
        assertNull(cleared.get("warningTemperatureCelsius"));
        assertEquals(0.3, cleared.get("cpuWeight"), "보내지 않은 가중치는 그대로");

        mockMvc.perform(delete("/api/v1/scheduler/policies/" + id)).andExpect(status().isOk());
    }

    @DisplayName("수정으로 다른 정책의 이름을 쓰면 409 자기 이름은 200")
    @Test
    void update_to_other_name_409() throws Exception {
        String a = name(), b = name();
        String bodyA = create("{\"name\":\"" + a + "\"}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String bodyB = create("{\"name\":\"" + b + "\"}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        update(idOf(bodyB), "{\"name\":\"" + a + "\"}").andExpect(status().isConflict());
        update(idOf(bodyB), "{\"name\":\"" + b + "\"}").andExpect(status().isOk());

        cleanup(bodyA);
        cleanup(bodyB);
    }

    @DisplayName("수정으로 가중치를 음수로 바꾸면 400 이고 저장되지 않는다")
    @Test
    void update_negative_weight_400() throws Exception {
        String body = create("{\"name\":\"" + name() + "\"}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = idOf(body);

        update(id, "{\"powerWeight\":-0.5}").andExpect(status().isBadRequest());
        // 실패한 수정은 반영되지 않아야 한다
        mockMvc.perform(get("/api/v1/scheduler/policies/" + id))
                .andExpect(jsonPath("$.data.powerWeight").value(0.2));

        cleanup(body);
    }

    @DisplayName("없는 정책 수정은 404")
    @Test
    void update_missing_policy_404() throws Exception {
        update(999999L, "{\"description\":\"x\"}").andExpect(status().isNotFound());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private static String name() { return "val-" + System.nanoTime(); }

    private ResultActions create(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/scheduler/policies").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions update(long id, String json) throws Exception {
        return mockMvc.perform(put("/api/v1/scheduler/policies/" + id).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(String body) {
        return (Map<String, Object>) objectMapper.readValue(body, Map.class).get("data");
    }

    private long idOf(String body) {
        return ((Number) data(body).get("id")).longValue();
    }

    private void cleanup(String body) throws Exception {
        mockMvc.perform(delete("/api/v1/scheduler/policies/" + idOf(body))).andExpect(status().isOk());
    }
}

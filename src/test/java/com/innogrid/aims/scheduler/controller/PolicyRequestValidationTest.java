package com.innogrid.aims.scheduler.controller;

import java.util.Map;

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

    @Test
    void 이름이_없으면_400() throws Exception {
        create("{\"cpuWeight\":0.3}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("정책 이름")));
    }

    @Test
    void 가중치가_음수면_400_점수_부호가_뒤집힌다() throws Exception {
        create("{\"name\":\"" + name() + "\",\"cpuWeight\":-1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("가중치는 0 이상")));
    }

    @Test
    void 가중치가_전부_0_이면_400() throws Exception {
        create("{\"name\":\"" + name() + "\",\"cpuWeight\":0,\"memoryWeight\":0,\"powerWeight\":0,"
                + "\"temperatureWeight\":0,\"networkWeight\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("가중치 합")));
    }

    @Test
    void 가중치_합이_1_이_아니어도_허용한다_비율_원칙() throws Exception {
        String body = create("{\"name\":\"" + name() + "\",\"cpuWeight\":25,\"memoryWeight\":25,\"powerWeight\":20,"
                + "\"temperatureWeight\":15,\"networkWeight\":15}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cpuWeight").value(25.0))
                .andReturn().getResponse().getContentAsString();
        cleanup(body);
    }

    @Test
    void 사용률_임계가_0_이하거나_100_초과면_400() throws Exception {
        create("{\"name\":\"" + name() + "\",\"maxCpuUsagePercent\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("maxCpuUsagePercent")));
        create("{\"name\":\"" + name() + "\",\"maxMemoryUsagePercent\":101}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("maxMemoryUsagePercent")));
    }

    @Test
    void 온도_임계가_0_이면_400() throws Exception {
        create("{\"name\":\"" + name() + "\",\"maxTemperatureCelsius\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("온도 임계")));
    }

    @Test
    void 모르는_기본_엔진은_400_아는_엔진은_200() throws Exception {
        create("{\"name\":\"" + name() + "\",\"defaultEngine\":\"nope\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("알 수 없는 스케줄링 엔진")));
        String body = create("{\"name\":\"" + name() + "\",\"defaultEngine\":\"power\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultEngine").value("power"))
                .andReturn().getResponse().getContentAsString();
        cleanup(body);
    }

    @Test
    void 이름이_중복되면_409_예전에는_DB_예외_500() throws Exception {
        String n = name();
        String body = create("{\"name\":\"" + n + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        create("{\"name\":\"" + n + "\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("이미 있는 정책 이름")));
        cleanup(body);
    }

    // ── 부분 수정 의미 ─────────────────────────────────────────

    @Test
    void 부분_수정은_보낸_키만_반영하고_명시적_null_은_비운다() throws Exception {
        String body = create("{\"name\":\"" + name() + "\",\"resourceType\":\"gpu\",\"defaultEngine\":\"power\","
                + "\"warningTemperatureCelsius\":60,\"cpuWeight\":0.3}")
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

    @Test
    void 수정으로_다른_정책의_이름을_쓰면_409_자기_이름은_200() throws Exception {
        String a = name(), b = name();
        String bodyA = create("{\"name\":\"" + a + "\"}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String bodyB = create("{\"name\":\"" + b + "\"}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        update(idOf(bodyB), "{\"name\":\"" + a + "\"}").andExpect(status().isConflict());
        update(idOf(bodyB), "{\"name\":\"" + b + "\"}").andExpect(status().isOk());

        cleanup(bodyA);
        cleanup(bodyB);
    }

    @Test
    void 수정으로_가중치를_음수로_바꾸면_400_이고_저장되지_않는다() throws Exception {
        String body = create("{\"name\":\"" + name() + "\"}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = idOf(body);

        update(id, "{\"powerWeight\":-0.5}").andExpect(status().isBadRequest());
        // 실패한 수정은 반영되지 않아야 한다
        mockMvc.perform(get("/api/v1/scheduler/policies/" + id))
                .andExpect(jsonPath("$.data.powerWeight").value(0.2));

        cleanup(body);
    }

    @Test
    void 없는_정책_수정은_404() throws Exception {
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

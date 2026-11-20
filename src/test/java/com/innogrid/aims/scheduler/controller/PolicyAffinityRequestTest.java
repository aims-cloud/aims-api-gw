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
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 정책 요청이 {@code affinityEnabled}를 받는가 — 응답(20필드)에는 있었지만 요청 DTO에 없어 API로 켤 수 없던 필드.
 * 게이트를 화면·API에서 켜고 끌 수 있어야 C5와 링크 점수가 쓰인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PolicyAffinityRequestTest {

    private static final String BASE = "/api/v1/scheduler/policies";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @DisplayName("생성 시 켤 수 있고 기본은 꺼져 있다")
    @Test
    void affinity_defaults_off_on_create() throws Exception {
        String on = "aff-on-" + System.nanoTime();
        Map<String, Object> created = data(mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + on + "\",\"affinityEnabled\":true}"))
                .andExpect(status().isOk()).andReturn());
        String off = "aff-off-" + System.nanoTime();
        Map<String, Object> plain = data(mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + off + "\"}"))
                .andExpect(status().isOk()).andReturn());
        try {
            assertEquals(Boolean.TRUE, created.get("affinityEnabled"));
            assertEquals(Boolean.FALSE, plain.get("affinityEnabled"), "기본값은 꺼짐 — 기존 배치 결과 불변");
            assertEquals(20, created.size(), "응답 필드 수는 그대로");
        } finally {
            mockMvc.perform(delete(BASE + "/" + created.get("id")));
            mockMvc.perform(delete(BASE + "/" + plain.get("id")));
        }
    }

    @DisplayName("부분 수정으로 켜고 끌 수 있고 키가 없으면 유지된다")
    @Test
    void patch_toggles_affinity_omitted_keeps() throws Exception {
        String name = "aff-put-" + System.nanoTime();
        Map<String, Object> created = data(mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()).andReturn());
        Object id = created.get("id");
        try {
            Map<String, Object> on = data(mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"affinityEnabled\":true}"))
                    .andExpect(status().isOk()).andReturn());
            assertEquals(Boolean.TRUE, on.get("affinityEnabled"));

            Map<String, Object> kept = data(mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"description\":\"d\"}"))
                    .andExpect(status().isOk()).andReturn());
            assertEquals(Boolean.TRUE, kept.get("affinityEnabled"), "키 없음 → 유지");

            Map<String, Object> off = data(mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"affinityEnabled\":false}"))
                    .andExpect(status().isOk()).andReturn());
            assertEquals(Boolean.FALSE, off.get("affinityEnabled"));
        } finally {
            mockMvc.perform(delete(BASE + "/" + id));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MvcResult r) throws Exception {
        Map<String, Object> body = objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
        return (Map<String, Object>) body.get("data");
    }
}

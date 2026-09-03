package com.innogrid.aims.scheduler.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스케줄 API 의 배치 순서 계약 — 생략하면 해석값 PRIORITY_THEN_AGE, 관대 파서, 모르는 값 400, 수정으로 변경, 응답 24필드.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class SchedulePlacementOrderApiTest {

    private static final String BASE = "/api/v1/scheduler/schedules";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void 생략하면_현행_해석값이고_응답은_24필드() throws Exception {
        String id = "poapi-" + Long.toHexString(System.nanoTime());
        JsonNode d = create("{\"scheduleId\":\"" + id + "\",\"name\":\"" + id + "\",\"cronExpression\":\"0 0 2 * * ?\",\"resourceType\":\"GPU\"}");
        try {
            assertEquals("PRIORITY_THEN_AGE", d.get("placementOrder").asString());
            assertEquals(24, d.propertyNames().size());
        } finally {
            mockMvc.perform(delete(BASE).param("ids", d.get("id").asString()));
        }
    }

    @Test
    void size_는_관대_파서로_받고_수정으로_바꿀_수_있으며_모르는_값은_400() throws Exception {
        String id = "poapi-" + Long.toHexString(System.nanoTime());
        JsonNode d = create("{\"scheduleId\":\"" + id + "\",\"name\":\"" + id + "\",\"cronExpression\":\"0 0 2 * * ?\",\"resourceType\":\"GPU\",\"placementOrder\":\"size\"}");
        try {
            assertEquals("PRIORITY_THEN_SIZE", d.get("placementOrder").asString());

            mockMvc.perform(put(BASE + "/" + d.get("id").asString()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"placementOrder\":\"age\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.placementOrder").value("PRIORITY_THEN_AGE"));

            mockMvc.perform(put(BASE + "/" + d.get("id").asString()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"placementOrder\":\"biggest\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("배치 순서")));
        } finally {
            mockMvc.perform(delete(BASE).param("ids", d.get("id").asString()));
        }
    }

    private JsonNode create(String json) throws Exception {
        String body = mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }
}

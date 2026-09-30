package com.innogrid.aims.scheduler.controller;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실행 미리보기({@code GET /schedules/{id}/preview})의 HTTP 계약 — 키 14개와 그 순서, null 규칙, 404.
 *
 * <p>이 시험은 트랜잭션을 걸지 않아 롤백이 없다(미리보기의 읽기 전용 트랜잭션을 그대로 탄다). 스케줄은 API 로 만들고 끝에 지운다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ScheduleRunPreviewApiTest {

    private static final String BASE = "/api/v1/scheduler/schedules";

    private static final List<String> KEYS = List.of(
            "id", "scheduleId", "enabled", "resourceType", "policyName", "policySource",
            "engine", "engineSource", "targetCount", "runLimit", "waitingCount", "exhaustedCount",
            "emptyReason", "emptyMessage");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ScheduleRepository scheduleRepository;

    @DisplayName("응답은 키 14개가 정해진 순서로 실리고 대상이 없으면 사유·문구가 채워진다")
    @Test
    void preview_response_has_14_keys_in_order_and_null_rules() throws Exception {
        String key = "prv-" + System.nanoTime();
        long id = create(key);
        try {
            String body = mockMvc.perform(get(BASE + "/" + id + "/preview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(id))
                    .andExpect(jsonPath("$.data.scheduleId").value(key))
                    .andExpect(jsonPath("$.data.enabled").value(true))
                    .andExpect(jsonPath("$.data.resourceType").value("VM"))
                    .andExpect(jsonPath("$.data.policyName").isString())
                    .andExpect(jsonPath("$.data.engine").isString())
                    .andExpect(jsonPath("$.data.runLimit").isNumber())
                    .andReturn().getResponse().getContentAsString();

            JsonNode data = objectMapper.readTree(body).get("data");
            List<String> names = new ArrayList<>();
            data.propertyNames().forEach(names::add);
            assertEquals(KEYS, names, "키 집합과 순서");

            long targets = data.get("targetCount").asLong();
            if (targets == 0) {
                assertTrue(data.get("emptyReason").isString(), "대상 0건이면 사유가 있다");
                assertTrue(data.get("emptyMessage").asString().startsWith("대상 없음"), data.get("emptyMessage").asString());
            } else {
                assertTrue(data.get("emptyReason").isNull(), "대상이 있으면 사유는 null");
                assertTrue(data.get("emptyMessage").isNull());
            }
            assertTrue(data.get("policySource").isString());
            assertTrue(data.get("engineSource").isString());

            // 미리보기는 최근 실행 정보를 건드리지 않는다
            Schedule reloaded = scheduleRepository.findById(id).orElseThrow();
            assertEquals(null, reloaded.getLastRunAt());
        } finally {
            mockMvc.perform(delete(BASE).param("ids", String.valueOf(id))).andExpect(status().isOk());
        }
    }

    @DisplayName("없는 스케줄의 미리보기는 404 이다")
    @Test
    void preview_missing_schedule_404() throws Exception {
        mockMvc.perform(get(BASE + "/999999999/preview")).andExpect(status().isNotFound());
    }

    private long create(String key) throws Exception {
        String body = mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleId\":\"" + key + "\",\"name\":\"미리보기 시험\",\"cronExpression\":\"0 0 2 * * ?\","
                                + "\"autoRun\":false,\"enabled\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }
}

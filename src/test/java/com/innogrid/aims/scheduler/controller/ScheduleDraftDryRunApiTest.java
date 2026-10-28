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

import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 저장 전 스케줄 모의 실행의 HTTP 계약 — 열일곱 키와 id null, 쓰기 없음, 400·403 래퍼, Swagger. 대상이 없는 종류를 써서 노드를 모으지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ScheduleDraftDryRunApiTest {

    private static final String BASE = "/api/v1/scheduler/schedules";
    private static final List<String> KEYS = List.of("id", "scheduleId", "enabled", "resourceType", "policyName", "policySource",
            "engine", "engineSource", "status", "message", "emptyReason", "targetCount", "successCount", "failedCount", "truncated",
            "clusterSnapshot", "decisions");
    private static final List<String> SNAPSHOT_KEYS = List.of("status", "knobsDigest", "nodeCount", "excludedCount",
            "powerMeasuredNodes", "measuredPowerWatts", "activeNodesBefore", "activeNodesAfter", "reclaimableWattsBefore",
            "reclaimableWattsAfter", "avgTemperatureBefore", "avgTemperatureAfter");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired SchedulingHistoryRepository historyRepository;

    @DisplayName("폼을 보내면 모의 실행의 열일곱 키를 그 순서로 돌려주고 id 는 null 이며 아무것도 쓰지 않는다")
    @Test
    void draft_returns_keys_with_null_id() throws Exception {
        long schedules = scheduleRepository.count();
        long runs = runRepository.count();
        long history = historyRepository.count();

        String body = mockMvc.perform(post(BASE + "/dry-run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleId\":\"  draft-dry  \",\"resourceType\":\"NPU\",\"engine\":\"power\",\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode d = objectMapper.readTree(body).get("data");
        assertEquals(KEYS, names(d), "모의 실행과 같은 열일곱 키·순서");
        assertEquals(SNAPSHOT_KEYS, names(d.get("clusterSnapshot")));
        assertTrue(d.get("id").isNull());
        assertEquals("draft-dry", d.get("scheduleId").asString());
        assertEquals(false, d.get("enabled").asBoolean());
        assertEquals("power", d.get("engine").asString());
        assertEquals("SCHEDULE", d.get("engineSource").asString());
        assertEquals("COMPLETED", d.get("status").asString());
        assertEquals("NO_TARGET", d.get("clusterSnapshot").get("status").asString());
        assertEquals(schedules, scheduleRepository.count(), "스케줄을 만들지 않는다");
        assertEquals(runs, runRepository.count());
        assertEquals(history, historyRepository.count());
    }

    @DisplayName("미인증은 403 이고 본문 없음·모르는 엔진은 400 이 공통 래퍼로 실리며 온도 상한을 지정한 요청은 200 으로 받는다")
    @Test
    void anonymous_403_and_errors_in_wrapper() throws Exception {
        mockMvc.perform(post(BASE + "/dry-run").with(anonymous()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/dry-run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"NPU\",\"maxTemperatureCelsius\":50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.clusterSnapshot.status").value("NO_TARGET"));
        mockMvc.perform(post(BASE + "/dry-run"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("요청 본문이 필요합니다"));
        mockMvc.perform(post(BASE + "/dry-run").contentType(MediaType.APPLICATION_JSON).content("{\"engine\":\"nope\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("알 수 없는 스케줄링 엔진입니다: nope"));
        mockMvc.perform(post(BASE + "/dry-run").contentType(MediaType.APPLICATION_JSON).content("{\"policyId\":999999999}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
    }

    @DisplayName("Swagger 에 경로가 있고 요청은 생성 스키마, 응답은 모의 실행 스키마를 가리키며 기존 두 경로는 그대로다")
    @Test
    void swagger_path_and_schemas() throws Exception {
        JsonNode doc = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode op = doc.get("paths").get(BASE + "/dry-run").get("post");
        assertEquals("저장 전 모의 실행", op.get("summary").asString());
        assertTrue(op.get("description").asString().contains("온도 상한을 지정하면 냉각 대기를 남기지 않습니다"), op.get("description").asString());
        assertTrue(op.get("requestBody").get("content").get("application/json").get("schema").get("$ref").asString().endsWith("/ScheduleRequest"));
        String resp = op.get("responses").get("200").get("content").properties().iterator().next().getValue().get("schema").get("$ref").asString();
        assertTrue(resp.endsWith("/ApiResponseScheduleDryRunResponse"), resp);
        assertNotNull(doc.get("paths").get(BASE + "/{id}/dry-run"), "저장된 스케줄 모의 실행 유지");
        assertNotNull(doc.get("paths").get(BASE + "/preview"), "저장 전 미리보기 유지");
    }

    private static List<String> names(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }
}

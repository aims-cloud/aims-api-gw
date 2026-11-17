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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장 전 미리보기({@code POST /schedules/preview})의 HTTP 계약 — 실행 미리보기와 같은 열네 키와 순서, id null,
 * 저장 없음, 오류 래퍼.
 *
 * <p>트랜잭션을 걸지 않는다(읽기 전용 트랜잭션을 그대로 탄다). 비교용 스케줄만 API로 만들고 끝에 지운다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ScheduleDraftPreviewApiTest {

    private static final String BASE = "/api/v1/scheduler/schedules";

    private static final List<String> KEYS = List.of(
            "id", "scheduleId", "enabled", "resourceType", "policyName", "policySource",
            "engine", "engineSource", "targetCount", "runLimit", "waitingCount", "exhaustedCount",
            "emptyReason", "emptyMessage");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ScheduleRepository scheduleRepository;

    private JsonNode data(String body) {
        return objectMapper.readTree(body).get("data");
    }

    private static List<String> keys(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    @DisplayName("응답은 실행 미리보기와 같은 열네 키 순서이고 id 는 null 이며 스케줄을 만들지 않는다")
    @Test
    void draft_has_14_keys_null_id_and_writes_nothing() throws Exception {
        long before = scheduleRepository.count();

        String body = mockMvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleId\":\"  draft-api  \",\"resourceType\":\"GPU\",\"engine\":\"power\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleId").value("draft-api"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.resourceType").value("GPU"))
                .andExpect(jsonPath("$.data.engine").value("power"))
                .andExpect(jsonPath("$.data.engineSource").value("SCHEDULE"))
                .andExpect(jsonPath("$.data.runLimit").isNumber())
                .andReturn().getResponse().getContentAsString();

        JsonNode d = data(body);
        assertEquals(KEYS, keys(d), "키 집합과 순서");
        assertTrue(d.get("id").isNull());
        assertEquals(before, scheduleRepository.count(), "미리보기가 스케줄을 만들면 안 된다");
    }

    @DisplayName("같은 값을 저장한 스케줄의 미리보기와 id 계열 두 키를 뺀 값이 같다")
    @Test
    void draft_equals_saved_preview_except_ids() throws Exception {
        String key = "draft-cmp-" + System.nanoTime();
        String form = "{\"scheduleId\":\"" + key + "\",\"name\":\"비교\",\"cronExpression\":\"0 0 2 * * ?\","
                + "\"autoRun\":false,\"enabled\":true,\"resourceType\":\"VM\",\"placementOrder\":\"size\"}";
        long id = data(mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(form))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        try {
            JsonNode saved = data(mockMvc.perform(get(BASE + "/" + id + "/preview")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            JsonNode draft = data(mockMvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON)
                    .content(form)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

            for (String k : KEYS) {
                if (k.equals("id")) continue;
                assertEquals(saved.get(k), draft.get(k), k);
            }
        } finally {
            mockMvc.perform(delete(BASE).param("ids", String.valueOf(id))).andExpect(status().isOk());
        }
    }

    @DisplayName("빈 본문은 200 이고 본문이 없으면 400, 없는 정책은 404 로 공통 래퍼에 사유가 실린다")
    @Test
    void empty_body_ok_missing_body_400_missing_policy_404() throws Exception {
        mockMvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resourceType").value("VM"))
                .andExpect(jsonPath("$.data.policyName").isString());
        mockMvc.perform(post(BASE + "/preview"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("요청 본문이 필요합니다"));
        mockMvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content("{\"policyId\":999999999}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("스케줄에 지정된 배치 정책을 찾을 수 없습니다: 999999999"));
        mockMvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON).content("{\"engine\":\"nope\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("알 수 없는 스케줄링 엔진입니다: nope"));
    }

    @DisplayName("사용 여부를 끄고 보내면 enabled 가 false 로 돌아온다")
    @Test
    void disabled_form_echoes_false_over_http() throws Exception {
        mockMvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"autoRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.resourceType").value("VM"));
    }

    @DisplayName("모르는 리소스 종류는 본문 읽기 단계의 400 과 사유로 돌아온다")
    @Test
    void unknown_resource_type_is_unreadable_body_400() throws Exception {
        mockMvc.perform(post(BASE + "/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"XYZ\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("요청 본문을 읽을 수 없습니다")));
    }

    @DisplayName("미인증이면 403 이다")
    @Test
    void anonymous_is_403() throws Exception {
        mockMvc.perform(post(BASE + "/preview").with(anonymous()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @DisplayName("요청 스키마는 생성 요청과 같고 Swagger 에 새 경로가 실린다")
    @Test
    void swagger_exposes_new_path_with_schedule_request_schema() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(body).get("paths");
        JsonNode op = paths.get(BASE + "/preview").get("post");
        assertEquals("저장 전 스케줄 미리보기", op.get("summary").asString());
        String ref = op.get("requestBody").get("content").get("application/json").get("schema").get("$ref").asString();
        assertTrue(ref.endsWith("/ScheduleRequest"), ref);
        assertTrue(paths.has(BASE + "/{id}/preview"), "기존 미리보기 경로는 그대로다");
    }
}

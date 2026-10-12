package com.innogrid.aims.scheduler.controller;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스케줄 편집 지정 해제의 HTTP 계약 — null 비움의 왕복, 미리보기 출처 변화, 상세 응답 그대로 PUT 무변경, 오류 래퍼, Swagger.
 *
 * <p>트랜잭션을 걸지 않는다 — 접두어 {@code clr-api-} 로 만든 스케줄과 정책을 끝에 지운다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ScheduleFieldClearApiTest {

    private static final String BASE = "/api/v1/scheduler/schedules";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SchedulingPolicyRepository policyRepository;

    private SchedulingPolicy policy;

    @BeforeEach
    void policy() {
        SchedulingPolicy p = new SchedulingPolicy("clr-api-policy-" + System.nanoTime(), "편집 지정 해제 시험");
        p.setActive(false);
        policy = policyRepository.save(p);
    }

    @AfterEach
    void clean() {
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith("clr-api-")).forEach(scheduleRepository::delete);
        policyRepository.findAll().stream().filter(p -> p.getName().startsWith("clr-api-policy-")).forEach(policyRepository::delete);
    }

    private JsonNode data(String body) {
        return objectMapper.readTree(body).get("data");
    }

    private long create(String key) throws Exception {
        String body = mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleId\":\"" + key + "\",\"name\":\"지정 해제\",\"cronExpression\":\"0 0 2 * * ?\","
                                + "\"autoRun\":false,\"enabled\":false,\"resourceType\":\"VM\",\"engine\":\"power\","
                                + "\"policyId\":" + policy.getId() + ",\"maxPowerBudgetWatts\":500,\"maxTemperatureCelsius\":70,"
                                + "\"description\":\"설명\",\"placementOrder\":\"size\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return data(body).get("id").asLong();
    }

    private JsonNode fetch(long id) throws Exception {
        return data(mockMvc.perform(get(BASE + "/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode preview(long id) throws Exception {
        return data(mockMvc.perform(get(BASE + "/" + id + "/preview")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @DisplayName("정책·엔진·상한·설명에 null 을 보내면 비워지고 다음 조회와 같다")
    @Test
    void put_null_clears_and_round_trips_to_get() throws Exception {
        long id = create("clr-api-null");

        JsonNode edited = data(mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyId\":null,\"engine\":null,\"maxPowerBudgetWatts\":null,\"maxTemperatureCelsius\":null,\"description\":null}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        for (String k : List.of("policyId", "engine", "maxPowerBudgetWatts", "maxTemperatureCelsius", "description")) {
            assertTrue(edited.get(k).isNull(), k);
        }
        assertEquals("PRIORITY_THEN_SIZE", edited.get("placementOrder").asString(), "보내지 않은 칸은 유지");
        ObjectNode a = (ObjectNode) edited.deepCopy();
        ObjectNode b = (ObjectNode) fetch(id).deepCopy();
        a.remove("updatedAt");
        b.remove("updatedAt");   // 저장 전 응답은 나노초, 다시 읽은 값은 DB 정밀도라 시각만 뺀다
        assertEquals(a, b, "응답 값 = 다음 조회 값");
    }

    @DisplayName("정책을 비우면 미리보기의 정책 출처가 스케줄 지정에서 상속으로 바뀐다")
    @Test
    void preview_policy_source_changes_after_clear() throws Exception {
        long id = create("clr-api-preview");
        assertEquals("SCHEDULE", preview(id).get("policySource").asString());
        assertEquals("SCHEDULE", preview(id).get("engineSource").asString());

        mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"policyId\":null,\"engine\":null}"))
                .andExpect(status().isOk());

        JsonNode p = preview(id);
        assertNotEquals("SCHEDULE", p.get("policySource").asString());
        assertNotEquals("SCHEDULE", p.get("engineSource").asString());
    }

    @DisplayName("상세 조회 응답을 그대로 보내면 수정 기록 외에는 바뀌지 않는다")
    @Test
    void detail_response_put_back_changes_nothing() throws Exception {
        long id = create("clr-api-roundtrip");
        JsonNode before = fetch(id);

        mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(before)))
                .andExpect(status().isOk());

        ObjectNode a = (ObjectNode) before.deepCopy();
        ObjectNode b = (ObjectNode) fetch(id).deepCopy();
        for (String k : List.of("updatedAt", "updatedBy", "updatedByName")) { a.remove(k); b.remove(k); }
        assertEquals(a, b);
    }

    @DisplayName("타입이 틀리면 400 과 사유를 공통 래퍼로 돌려주고 값은 그대로다")
    @Test
    void type_error_message_is_400() throws Exception {
        long id = create("clr-api-type");
        mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"maxTemperatureCelsius\":\"70\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("온도 상한(℃)은(는) 숫자여야 합니다"));
        mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"policyId\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("배치 정책 id은(는) 정수여야 합니다"));
        mockMvc.perform(put(BASE + "/" + id))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("요청 본문이 필요합니다"));
        mockMvc.perform(put(BASE + "/999999999").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        assertEquals(70.0, fetch(id).get("maxTemperatureCelsius").asDouble());
    }

    @DisplayName("모르는 리소스 종류는 본문 읽기 단계의 400 이다")
    @Test
    void unknown_resource_type_400_at_read_stage() throws Exception {
        long id = create("clr-api-enum");
        mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"resourceType\":\"XYZ\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("요청 본문을 읽을 수 없습니다")));
    }

    @DisplayName("Swagger 에서 편집은 편집 요청 스키마를, 생성과 저장 전 미리보기는 생성 요청 스키마를 쓴다")
    @Test
    void swagger_put_uses_update_request_and_post_keeps_schedule_request() throws Exception {
        JsonNode paths = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("paths");
        assertTrue(ref(paths, BASE + "/{id}", "put").endsWith("/ScheduleUpdateRequest"));
        assertTrue(ref(paths, BASE, "post").endsWith("/ScheduleRequest"));
        assertTrue(ref(paths, BASE + "/preview", "post").endsWith("/ScheduleRequest"));
    }

    private static String ref(JsonNode paths, String path, String method) {
        return paths.get(path).get(method).get("requestBody").get("content").get("application/json").get("schema").get("$ref").asString();
    }

    @DisplayName("미인증이면 403 이다")
    @Test
    void unauthenticated_403() throws Exception {
        mockMvc.perform(put(BASE + "/1").with(anonymous()).contentType(MediaType.APPLICATION_JSON).content("{\"policyId\":null}"))
                .andExpect(status().isForbidden());
    }
}

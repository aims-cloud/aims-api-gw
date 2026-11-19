package com.innogrid.aims.scheduler.controller;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.innogrid.aims.scheduler.repository.NodePlacementHoldRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 노드 배치 중지 원장 API — 응답 9필드 순서, 검증(400), 중복(409), 없는 행(404), 사유만 고치는 수정, 목록 필터 · 순서,
 * Swagger operationId.
 *
 * <p>다른 원장 API 테스트와 같은 구성(가짜 빈 없음)이라 같은 스프링 컨텍스트를 쓴다. 롤백이 없어 노드 id 접두어로 지운다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class NodePlacementHoldApiTest {

    private static final String BASE = "/api/v1/scheduler/node-placement-holds";
    private static final List<String> FIELDS = List.of("id", "nodeId", "reason", "createdBy", "createdByName",
            "updatedBy", "updatedByName", "createdAt", "updatedAt");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NodePlacementHoldRepository repository;

    @AfterEach
    void clean() {
        repository.findAll().stream().filter(h -> h.getNodeId().startsWith("nhapi-")).forEach(repository::delete);
    }

    @DisplayName("등록 응답은 9필드가 선언 순서이고 노드 id 앞뒤 공백을 빼며 호출자 계정이 남는다")
    @Test
    void create_returns_nine_fields() throws Exception {
        JsonNode d = create("{\"nodeId\":\"  nhapi-a:9100 \",\"reason\":\"디스크 교체\"}");

        assertEquals(FIELDS, names(d));
        assertEquals("nhapi-a:9100", d.get("nodeId").asString());
        assertEquals("디스크 교체", d.get("reason").asString());
        assertEquals("user", d.get("createdBy").asString());
        assertFalse(d.get("createdAt").isNull());
    }

    @DisplayName("본문 없음 · 빈 노드 id · 길이 초과 · 문자열이 아닌 사유는 400 이다")
    @Test
    void invalid_requests_are_400() throws Exception {
        mockMvc.perform(post(BASE)).andExpect(status().isBadRequest());
        for (String body : List.of("{}", "{\"nodeId\":\"  \"}", "{\"nodeId\":\"nhapi-" + "x".repeat(100) + "\"}",
                "{\"nodeId\":\"nhapi-b\",\"reason\":5}",
                "{\"nodeId\":\"nhapi-b\",\"reason\":\"" + "r".repeat(301) + "\"}")) {
            mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertTrue(repository.findByNodeId("nhapi-b").isEmpty());
    }

    @DisplayName("같은 노드를 다시 등록하면 409 이고 대소문자가 다르면 다른 노드다")
    @Test
    void duplicate_is_409_and_case_sensitive() throws Exception {
        create("{\"nodeId\":\"nhapi-c\"}");

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"nodeId\":\" nhapi-c \"}"))
                .andExpect(status().isConflict());
        assertEquals("nhapi-C", create("{\"nodeId\":\"nhapi-C\"}").get("nodeId").asString());
    }

    @DisplayName("수정은 사유만 고치고 중지 시작 시각은 그대로이며 노드를 바꾸려 하면 400 이다")
    @Test
    void update_changes_reason_only() throws Exception {
        long id = create("{\"nodeId\":\"nhapi-d\",\"reason\":\"점검\"}").get("id").asLong();
        // 저장 정밀도로 읽은 값 — 등록 응답의 시각은 저장 전 값이라 정밀도가 다를 수 있다
        JsonNode stored = data(mockMvc.perform(get(BASE + "/" + id)).andExpect(status().isOk()).andReturn());

        JsonNode kept = update(id, "{}");
        assertEquals("점검", kept.get("reason").asString(), "reason 키가 없으면 유지");
        JsonNode changed = update(id, "{\"nodeId\":\"nhapi-d\",\"reason\":\"장애 의심\"}");
        assertEquals("장애 의심", changed.get("reason").asString());
        assertEquals(stored.get("createdAt").asString(), changed.get("createdAt").asString(), "중지 시작 시각은 그대로");
        assertTrue(update(id, "{\"reason\":null}").get("reason").isNull(), "null 이면 비운다");

        mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"nhapi-z\"}"))
                .andExpect(status().isBadRequest());
        assertEquals("nhapi-d", repository.findById(id).orElseThrow().getNodeId());
    }

    @DisplayName("없는 행의 조회 · 수정 · 해제는 404 이고 해제하면 목록에서 빠진다")
    @Test
    void missing_rows_are_404_and_delete_removes() throws Exception {
        long id = create("{\"nodeId\":\"nhapi-e\"}").get("id").asLong();

        mockMvc.perform(delete(BASE + "/" + id)).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(get(BASE).param("nodeId", "nhapi-e")).andExpect(jsonPath("$.data").isEmpty());
    }

    @DisplayName("목록은 노드 id 순이고 nodeId 필터는 부분 일치 · 대소문자 무시다")
    @Test
    void list_is_sorted_and_filtered() throws Exception {
        create("{\"nodeId\":\"nhapi-list-b\"}");
        create("{\"nodeId\":\"nhapi-list-a\"}");
        create("{\"nodeId\":\"nhapi-other\"}");

        JsonNode filtered = data(mockMvc.perform(get(BASE).param("nodeId", "NHAPI-LIST"))
                .andExpect(status().isOk()).andReturn());
        List<String> ids = new ArrayList<>();
        filtered.forEach(n -> ids.add(n.get("nodeId").asString()));
        assertEquals(List.of("nhapi-list-a", "nhapi-list-b"), ids);
    }

    @DisplayName("새 작업 다섯의 operationId 는 번호 없는 메서드 이름이다(다른 컨트롤러의 번호를 밀지 않음)")
    @Test
    void operation_ids_are_unsuffixed_method_names() throws Exception {
        JsonNode paths = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("paths");
        JsonNode list = paths.get(BASE);
        JsonNode item = paths.get(BASE + "/{holdId}");

        assertEquals(List.of("listHolds", "createHold", "getHold", "updateHold", "deleteHold"),
                List.of(list.get("get").get("operationId").asString(), list.get("post").get("operationId").asString(),
                        item.get("get").get("operationId").asString(), item.get("put").get("operationId").asString(),
                        item.get("delete").get("operationId").asString()));
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private JsonNode create(String body) throws Exception {
        return data(mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode update(long id, String body) throws Exception {
        return data(mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("data");
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}

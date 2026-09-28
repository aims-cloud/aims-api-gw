package com.innogrid.aims.scheduler.controller;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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

import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MDC 전력 예산 원장 API 5종의 HTTP 계약 — 11필드, 상태 코드, 필터, 인증. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class MdcPowerBudgetApiTest {

    static final Set<String> FIELDS = new TreeSet<>(List.of("id", "mdcId", "budgetWatts", "source", "description",
            "createdBy", "createdByName", "updatedBy", "updatedByName", "createdAt", "updatedAt"));

    private static final String BASE = "/api/v1/scheduler/mdc-power-budgets";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MdcPowerBudgetRepository repository;

    @AfterEach
    void clean() {
        repository.findAll().stream().filter(b -> b.getMdcId().startsWith("mpbapi-")).forEach(repository::delete);
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("data");
    }

    private JsonNode create(String body) throws Exception {
        return data(mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    @DisplayName("등록 응답은 11필드이고 기본 출처 MANUAL·호출자 계정이 남는다")
    @Test
    void create_response_11_fields() throws Exception {
        JsonNode d = create("{\"mdcId\":\"mpbapi-seoul\",\"budgetWatts\":4000,\"description\":\"수전 계약\"}");
        assertEquals(FIELDS, new TreeSet<>(d.propertyNames()));
        assertEquals(4000.0, d.get("budgetWatts").asDouble());
        assertEquals("MANUAL", d.get("source").asString());
        assertEquals("user", d.get("createdBy").asString());
        assertFalse(d.get("createdAt").isNull());
    }

    @DisplayName("예산이 0 이하·무한대·누락이면 400, 크기가 커도 받는다")
    @Test
    void budget_validation() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"mdcId\":\"mpbapi-b\",\"budgetWatts\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"mdcId\":\"mpbapi-b\",\"budgetWatts\":-5}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"mdcId\":\"mpbapi-b\",\"budgetWatts\":1e400}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"mdcId\":\"mpbapi-b\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE)).andExpect(status().isBadRequest());
        assertEquals(450000.0, create("{\"mdcId\":\"mpbapi-huge\",\"budgetWatts\":450000}").get("budgetWatts").asDouble());
    }

    @DisplayName("같은 MDC 재등록은 409, 모르는 출처는 400, 없는 id 는 404")
    @Test
    void conflict_and_not_found() throws Exception {
        JsonNode created = create("{\"mdcId\":\"mpbapi-c\",\"budgetWatts\":1000}");
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"mdcId\":\"mpbapi-c\",\"budgetWatts\":2000}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mdcId\":\"mpbapi-d\",\"budgetWatts\":1000,\"source\":\"guess\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/999999")).andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/999999")).andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/" + created.get("id").asLong())).andExpect(status().isOk());
    }

    @DisplayName("부분 수정은 보낸 키만 반영하고, 목록은 식별자 순·부분 일치 필터가 걸리며, 삭제하면 404")
    @Test
    void update_list_delete() throws Exception {
        JsonNode z = create("{\"mdcId\":\"mpbapi-zulu\",\"budgetWatts\":1000,\"source\":\"SPEC_SHEET\",\"description\":\"처음\"}");
        create("{\"mdcId\":\"mpbapi-alpha\",\"budgetWatts\":2000}");

        mockMvc.perform(put(BASE + "/" + z.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"budgetWatts\":6000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.budgetWatts").value(6000.0))
                .andExpect(jsonPath("$.data.source").value("SPEC_SHEET"))
                .andExpect(jsonPath("$.data.description").value("처음"));
        mockMvc.perform(put(BASE + "/" + z.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.description").doesNotExist());

        JsonNode list = data(mockMvc.perform(get(BASE).param("mdcId", "mpbapi-")).andExpect(status().isOk()).andReturn());
        assertEquals(2, list.size());
        assertEquals("mpbapi-alpha", list.get(0).get("mdcId").asString());
        assertEquals(1, data(mockMvc.perform(get(BASE).param("mdcId", "MPBAPI-ZULU")).andReturn()).size());

        mockMvc.perform(delete(BASE + "/" + z.get("id").asLong())).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + z.get("id").asLong())).andExpect(status().isNotFound());
    }

    @DisplayName("미인증은 403 이고 기존 노드 응답은 27필드 그대로다")
    @Test
    void auth_and_node_contract_unchanged() throws Exception {
        mockMvc.perform(get(BASE).with(anonymous())).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE).with(anonymous()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mdcId\":\"x\",\"budgetWatts\":1}")).andExpect(status().isForbidden());
        JsonNode nodes = data(mockMvc.perform(get("/api/v1/scheduler/nodes")).andExpect(status().isOk()).andReturn());
        if (!nodes.isEmpty()) assertEquals(27, nodes.get(0).propertyNames().size());
    }
}

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

import com.innogrid.aims.scheduler.repository.NodePowerSpecRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 전력 사양 원장 API 5종의 HTTP 계약 — 11필드, 상태 코드, 필터, 인증. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class NodePowerSpecApiTest {

    static final Set<String> FIELDS = new TreeSet<>(List.of("id", "nodeId", "capacityWatts", "source", "description",
            "createdBy", "createdByName", "updatedBy", "updatedByName", "createdAt", "updatedAt"));

    private static final String BASE = "/api/v1/scheduler/node-power-specs";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NodePowerSpecRepository repository;

    @AfterEach
    void clean() {
        repository.findAll().stream().filter(s -> s.getNodeId().startsWith("npsapi-")).forEach(repository::delete);
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
        JsonNode d = create("{\"nodeId\":\"npsapi-a:9100\",\"capacityWatts\":900,\"description\":\"GPU 서버\"}");
        assertEquals(FIELDS, new TreeSet<>(d.propertyNames()));
        assertEquals(900.0, d.get("capacityWatts").asDouble());
        assertEquals("MANUAL", d.get("source").asString());
        assertEquals("user", d.get("createdBy").asString());
        assertFalse(d.get("createdAt").isNull());
    }

    @DisplayName("정격이 0 이하·NaN·무한대·누락이면 400, 크기가 커도 받는다")
    @Test
    void capacity_validation() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"nodeId\":\"npsapi-b:9100\",\"capacityWatts\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"nodeId\":\"npsapi-b:9100\",\"capacityWatts\":-5}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"nodeId\":\"npsapi-b:9100\",\"capacityWatts\":1e400}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"nodeId\":\"npsapi-b:9100\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE)).andExpect(status().isBadRequest());
        assertEquals(45000.0, create("{\"nodeId\":\"npsapi-rack:9100\",\"capacityWatts\":45000}").get("capacityWatts").asDouble());
    }

    @DisplayName("같은 노드 재등록은 409, 모르는 출처는 400, 없는 id 는 404")
    @Test
    void conflict_and_not_found() throws Exception {
        JsonNode created = create("{\"nodeId\":\"npsapi-c:9100\",\"capacityWatts\":500}");
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"nodeId\":\"npsapi-c:9100\",\"capacityWatts\":700}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"npsapi-d:9100\",\"capacityWatts\":500,\"source\":\"guess\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/999999")).andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/999999")).andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/" + created.get("id").asLong())).andExpect(status().isOk());
    }

    @DisplayName("부분 수정은 보낸 키만 반영하고, 목록은 식별자 순·부분 일치 필터가 걸리며, 삭제하면 404")
    @Test
    void update_list_delete() throws Exception {
        JsonNode z = create("{\"nodeId\":\"npsapi-z:9100\",\"capacityWatts\":500,\"source\":\"SPEC_SHEET\",\"description\":\"처음\"}");
        create("{\"nodeId\":\"npsapi-a:9100\",\"capacityWatts\":600}");

        mockMvc.perform(put(BASE + "/" + z.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capacityWatts\":1200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacityWatts").value(1200.0))
                .andExpect(jsonPath("$.data.source").value("SPEC_SHEET"))
                .andExpect(jsonPath("$.data.description").value("처음"));
        mockMvc.perform(put(BASE + "/" + z.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.description").doesNotExist());

        JsonNode list = data(mockMvc.perform(get(BASE).param("nodeId", "npsapi-")).andExpect(status().isOk()).andReturn());
        assertEquals(2, list.size());
        assertEquals("npsapi-a:9100", list.get(0).get("nodeId").asString());
        assertEquals(1, data(mockMvc.perform(get(BASE).param("nodeId", "NPSAPI-Z")).andReturn()).size());

        mockMvc.perform(delete(BASE + "/" + z.get("id").asLong())).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + z.get("id").asLong())).andExpect(status().isNotFound());
    }

    @DisplayName("미인증은 403 이고 기존 노드 응답은 21필드 그대로다")
    @Test
    void auth_and_node_contract_unchanged() throws Exception {
        mockMvc.perform(get(BASE).with(anonymous())).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE).with(anonymous()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nodeId\":\"x\",\"capacityWatts\":1}")).andExpect(status().isForbidden());
        JsonNode nodes = data(mockMvc.perform(get("/api/v1/scheduler/nodes")).andExpect(status().isOk()).andReturn());
        if (!nodes.isEmpty()) assertEquals(21, nodes.get(0).propertyNames().size());
    }
}

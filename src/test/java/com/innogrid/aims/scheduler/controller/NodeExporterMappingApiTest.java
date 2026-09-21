package com.innogrid.aims.scheduler.controller;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.innogrid.aims.scheduler.repository.NodeExporterMappingRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 익스포터 대응 원장 API 계약 — 11필드, 검증 400, 중복·공유 instance 409, 부분 수정, 미인증 403.
 *
 * <p>원장이 수집 결과에 닿는지는 여기서 보지 않는다 — 이 컨텍스트는 실 게이트웨이라 노드가 없다.
 * {@code ExporterMappingCollectionTest} 가 모의 클러스터로 본다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class NodeExporterMappingApiTest {

    static final Set<String> FIELDS = new TreeSet<>(List.of("id", "nodeId", "dcgmInstance", "ipmiInstance", "description",
            "createdBy", "createdByName", "updatedBy", "updatedByName", "createdAt", "updatedAt"));

    private static final String BASE = "/api/v1/scheduler/node-exporter-mappings";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NodeExporterMappingRepository repository;

    @AfterEach
    void clean() {
        repository.findAll().stream()
                .filter(m -> m.getNodeId().startsWith("nemapi-"))
                .forEach(repository::delete);
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("data");
    }

    private JsonNode create(String body) throws Exception {
        return data(mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    @DisplayName("등록 응답은 11필드이고 두 익스포터 칸은 비워 둘 수 있으며 호출자 계정이 남는다")
    @Test
    void create_response_11_fields() throws Exception {
        JsonNode d = create("{\"nodeId\":\"nemapi-a:9100\",\"dcgmInstance\":\"nemapi-a:9400\",\"description\":\"GPU 서버\"}");
        assertEquals(FIELDS, new TreeSet<>(d.propertyNames()));
        assertEquals("nemapi-a:9400", d.get("dcgmInstance").asString());
        assertTrue(d.get("ipmiInstance").isNull(), "비운 칸은 null — 수집기가 호스트명 일치로 대체한다");
        assertEquals("user", d.get("createdBy").asString());
        assertFalse(d.get("createdAt").isNull());
    }

    @DisplayName("노드 식별자 누락·빈 본문·노드와 같은 instance·문자열 아닌 값은 400")
    @Test
    void validation() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"dcgmInstance\":\"x:9400\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE)).andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"nemapi-b:9100\",\"ipmiInstance\":\"nemapi-b:9100\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"nemapi-b:9100\",\"ipmiInstance\":123}"))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("같은 노드 재등록은 409, 없는 id 는 404")
    @Test
    void conflict_and_not_found() throws Exception {
        JsonNode created = create("{\"nodeId\":\"nemapi-c:9100\"}");
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"nodeId\":\"nemapi-c:9100\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get(BASE + "/999999")).andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/999999")).andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/" + created.get("id").asLong())).andExpect(status().isOk());
    }

    @DisplayName("부분 수정은 보낸 키만 반영하고 null 은 비우며, 목록은 식별자 순·부분 일치 필터가 걸리고, 삭제하면 404")
    @Test
    void update_list_delete() throws Exception {
        JsonNode z = create("{\"nodeId\":\"nemapi-z:9100\",\"dcgmInstance\":\"nemapi-z:9400\",\"ipmiInstance\":\"ipmi-exporter:9290\",\"description\":\"처음\"}");
        create("{\"nodeId\":\"nemapi-a:9100\"}");

        mockMvc.perform(put(BASE + "/" + z.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dcgmInstance\":\"nemapi-z:9401\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dcgmInstance").value("nemapi-z:9401"))
                .andExpect(jsonPath("$.data.ipmiInstance").value("ipmi-exporter:9290"))
                .andExpect(jsonPath("$.data.description").value("처음"));
        mockMvc.perform(put(BASE + "/" + z.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ipmiInstance\":null,\"description\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ipmiInstance").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.description").value(org.hamcrest.Matchers.nullValue()));

        JsonNode list = data(mockMvc.perform(get(BASE).param("nodeId", "nemapi-")).andExpect(status().isOk()).andReturn());
        assertEquals(2, list.size());
        assertEquals("nemapi-a:9100", list.get(0).get("nodeId").asString());
        assertEquals(1, data(mockMvc.perform(get(BASE).param("nodeId", "NEMAPI-Z")).andReturn()).size());

        mockMvc.perform(delete(BASE + "/" + z.get("id").asLong())).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + z.get("id").asLong())).andExpect(status().isNotFound());
    }

    @DisplayName("다른 노드가 쓰는 instance 는 409 — 한 시리즈를 두 노드에 넣으면 값이 두 번 세어진다. 대소문자만 다른 노드도 409")
    @Test
    void shared_instance_and_case_variant_node_are_conflicts() throws Exception {
        JsonNode d = create("{\"nodeId\":\"nemapi-d:9100\",\"ipmiInstance\":\"ipmi-exporter:9290\",\"dcgmInstance\":\"nemapi-d:9400\"}");
        JsonNode e = create("{\"nodeId\":\"nemapi-e:9100\"}");

        MvcResult shared = mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"nemapi-f:9100\",\"ipmiInstance\":\"ipmi-exporter:9290\"}"))
                .andExpect(status().isConflict()).andReturn();
        assertTrue(shared.getResponse().getContentAsString().contains("nemapi-d:9100"), "누가 쓰는지 알려 준다");
        mockMvc.perform(put(BASE + "/" + e.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dcgmInstance\":\"nemapi-d:9400\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put(BASE + "/" + d.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ipmiInstance\":\"ipmi-exporter:9290\"}"))
                .andExpect(status().isOk());   // 자기 행의 같은 값은 충돌이 아니다

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"nodeId\":\"NEMAPI-D:9100\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put(BASE + "/" + e.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"Nemapi-D:9100\"}"))
                .andExpect(status().isConflict());
    }

    @DisplayName("미인증은 403 이고 기존 노드 응답은 21필드 그대로다")
    @Test
    void auth_and_node_contract_unchanged() throws Exception {
        mockMvc.perform(get(BASE).with(anonymous())).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE).with(anonymous()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nodeId\":\"x\"}")).andExpect(status().isForbidden());
        JsonNode nodes = data(mockMvc.perform(get("/api/v1/scheduler/nodes")).andExpect(status().isOk()).andReturn());
        if (!nodes.isEmpty()) assertEquals(21, nodes.get(0).propertyNames().size());
    }
}

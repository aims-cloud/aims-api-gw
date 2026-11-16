package com.innogrid.aims.scheduler.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.model.InventoryStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Nova 인벤토리 조회 API — 응답 모양과 Swagger 문서.
 *
 * <p>테스트 설정(src/test/resources/application.yml)에는 openstack 블록이 없어 접속 정보가 비어 있으므로 조회는 NOT_CONFIGURED 로 끝난다.
 * 상태별 분류와 변환은 {@code NovaInventoryGatewayTest} 가 실제 HTTP 응답으로 확인한다. 테스트 컨텍스트는 다른 API 테스트와 공유하도록
 * 세 애너테이션만 붙이고 빈 교체 · 프로파일 · 속성 지정을 두지 않는다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class NovaInventoryApiTest {

    private static final String PATH = "/api/v1/scheduler/inventory/nova";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @DisplayName("접속 정보가 없는 설정에서 200 · NOT_CONFIGURED 이고 목록은 비며 건수는 null 이다")
    @Test
    void unconfigured_openstack_answers_not_configured() throws Exception {
        String body = mockMvc.perform(get(PATH)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");

        assertEquals("NOVA", data.get("source").asString());
        assertEquals("NOT_CONFIGURED", data.get("status").asString());
        assertEquals(InventoryStatus.NOT_CONFIGURED.message(), data.get("message").asString());
        assertTrue(data.get("hypervisorCount").isNull() && data.get("serverCount").isNull(), "모르는 건수는 0 이 아니라 null");
        assertEquals(0, data.get("nodes").size());
        assertEquals(0, data.get("servers").size());
        assertEquals(List.of("source", "status", "message", "checkedAt", "elapsedMs", "unavailable", "hypervisorCount", "usableNodeCount",
                "serverCount", "convertibleServerCount", "nodes", "servers"), names(data));
    }

    @DisplayName("Swagger 에 새 경로가 있고 status 는 값 목록을 직접 싣는 열거형이며 중첩 스키마는 NovaInventory 이름을 쓴다")
    @Test
    void swagger_lists_path_inline_status_and_prefixed_nested_schemas() throws Exception {
        JsonNode docs = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
        assertNotNull(docs.path("paths").path(PATH).get("get"), "GET " + PATH);

        JsonNode schemas = docs.path("components").path("schemas");
        JsonNode status = schemas.path("NovaInventoryResponse").path("properties").path("status");
        assertFalse(status.has("$ref"), "status 는 열거형 스키마를 가리키지 않는다");
        assertEquals(Arrays.stream(InventoryStatus.values()).map(Enum::name).toList(), enumValues(status), "값 목록 = 선언 순서");
        assertFalse(schemas.has("InventoryStatus"), "열거형 스키마가 따로 생기지 않는다");

        assertEquals("#/components/schemas/NovaInventoryNode",
                schemas.path("NovaInventoryResponse").path("properties").path("nodes").path("items").path("$ref").asString());
        assertEquals("#/components/schemas/NovaInventoryServer",
                schemas.path("NovaInventoryResponse").path("properties").path("servers").path("items").path("$ref").asString());
        assertTrue(schemas.path("NovaInventoryNode").path("properties").has("allocatedCpu"));
        assertTrue(schemas.path("NovaInventoryServer").path("properties").has("requiredMemoryMb"));
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static List<String> enumValues(JsonNode schema) {
        List<String> values = new ArrayList<>();
        schema.path("enum").forEach(v -> values.add(v.asString()));
        return values;
    }
}

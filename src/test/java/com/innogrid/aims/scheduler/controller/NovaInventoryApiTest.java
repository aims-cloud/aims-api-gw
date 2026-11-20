package com.innogrid.aims.scheduler.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.model.InventoryStatus;
import com.innogrid.aims.scheduler.model.NovaDryRunStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Nova 인벤토리 조회 · 모의 배치 API — 응답 모양과 Swagger 문서.
 *
 * <p>테스트 설정(src/test/resources/application.yml)에는 openstack 블록이 없어 접속 정보가 비어 있으므로 조회는 NOT_CONFIGURED로 끝난다.
 * 상태별 분류와 변환은 {@code NovaInventoryGatewayTest}가 실제 HTTP 응답으로 확인한다. 테스트 컨텍스트는 다른 API 테스트와 공유하도록
 * 세 애너테이션만 붙이고 빈 교체 · 프로파일 · 속성 지정을 두지 않는다. 모의 배치 플래그를 켠 경로는 주입된 설정 값을 그 테스트 안에서만
 * 켰다가 되돌린다(서비스가 호출마다 읽는다).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class NovaInventoryApiTest {

    private static final String PATH = "/api/v1/scheduler/inventory/nova";
    private static final String DRY_RUN = PATH + "/dry-run";
    private static final List<String> DRY_RUN_FIELDS = List.of("source", "status", "message", "inventoryStatus", "unavailable", "policyName", "engine",
            "cpuLimitPercent", "memoryLimitPercent", "order", "checkedAt", "elapsedMs", "nodeCount", "vmCount", "excludedCount", "placedCount",
            "failedCount", "failedByReason", "hostsInUseBefore", "hostsInUseAfter", "hostChangedCount", "overLimitNodeCount", "nodes",
            "decisions", "excluded");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SchedulerProperties properties;

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

    @DisplayName("모의 배치는 플래그 기본값(꺼짐)에서 200 · DISABLED 이고 모르는 건수는 null 이다")
    @Test
    void dry_run_is_disabled_by_default() throws Exception {
        JsonNode data = dryRun();

        assertEquals("NOVA", data.get("source").asString());
        assertEquals("DISABLED", data.get("status").asString());
        assertEquals(NovaDryRunStatus.DISABLED.message(), data.get("message").asString());
        assertTrue(data.get("inventoryStatus").isNull() && data.get("nodeCount").isNull() && data.get("placedCount").isNull());
        assertEquals("LARGEST_FIRST", data.get("order").asString());
        assertEquals(0, data.get("decisions").size());
        assertEquals(DRY_RUN_FIELDS, names(data));
    }

    @DisplayName("플래그를 켜면 Nova 를 읽고, 접속 정보가 없는 설정에서는 INVENTORY_FAILED · inventoryStatus NOT_CONFIGURED 다")
    @Test
    void dry_run_with_flag_on_reads_inventory() throws Exception {
        properties.getInventory().setSimulationEnabled(true);
        JsonNode data;
        try {
            data = dryRun();
        } finally {
            properties.getInventory().setSimulationEnabled(false);
        }

        assertEquals("INVENTORY_FAILED", data.get("status").asString());
        assertEquals("NOT_CONFIGURED", data.get("inventoryStatus").asString());
        assertTrue(data.get("nodeCount").isNull() && data.get("vmCount").isNull() && data.get("policyName").isNull(), "못 읽은 건수는 null");
        assertEquals(DRY_RUN_FIELDS, names(data));
    }

    @DisplayName("Swagger 에 모의 배치 경로가 있고 두 상태는 값 목록을 직접 싣는 열거형이며 중첩 스키마는 NovaDryRun 이름을 쓴다")
    @Test
    void swagger_lists_dry_run_with_inline_statuses_and_prefixed_schemas() throws Exception {
        JsonNode docs = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
        assertNotNull(docs.path("paths").path(DRY_RUN).get("post"), "POST " + DRY_RUN);

        JsonNode schemas = docs.path("components").path("schemas");
        JsonNode props = schemas.path("NovaDryRunResponse").path("properties");
        assertEquals(Arrays.stream(NovaDryRunStatus.values()).map(Enum::name).toList(), enumValues(props.path("status")));
        assertEquals(Arrays.stream(InventoryStatus.values()).map(Enum::name).toList(), enumValues(props.path("inventoryStatus")));
        assertFalse(schemas.has("NovaDryRunStatus"), "열거형 스키마가 따로 생기지 않는다");
        assertEquals("#/components/schemas/NovaDryRunNode", props.path("nodes").path("items").path("$ref").asString());
        assertEquals("#/components/schemas/NovaDryRunDecision", props.path("decisions").path("items").path("$ref").asString());
        assertEquals("#/components/schemas/NovaDryRunExcluded", props.path("excluded").path("items").path("$ref").asString());
        assertTrue(props.has("overLimitNodeCount"));
        JsonNode node = schemas.path("NovaDryRunNode").path("properties");
        assertTrue(node.has("simulatedCpuUsagePercent") && node.has("simulatedMemoryUsagePercent") && node.has("overLimit"),
                "놓은 뒤 사용률 · 상한 초과");

        // 설명 문구 — 놓기 전 상한 · 놓은 뒤 초과 · 엔진 차이의 원인 · flavor를 못 읽은 경우 · 반올림 전 판정 · 총량 0
        String post = docs.path("paths").path(DRY_RUN).path("post").path("description").asString();
        assertFalse(post.contains("엔진에 따른 차이도 없습니다"), "엔진에 따라 VM별 결정이 다를 수 있다");
        assertTrue(post.contains("놓은 뒤 사용률은 상한을 넘을 수 있고") && post.contains("VM별 결정은 다를 수 있습니다"), post);
        assertTrue(post.contains("반올림 뒤 동점 여부가 엔진마다 달라") && !post.contains("점수가 같을 때"), post);
        String response = schemas.path("NovaDryRunResponse").path("description").asString();
        assertFalse(response.contains("정책 상한 아래"), response);
        assertTrue(response.contains("놓기 전 값"), response);
        assertTrue(props.path("engine").path("description").asString().contains("VM별 결정은 다를 수 있다"));
        assertTrue(props.path("engine").path("description").asString().contains("반올림 뒤 동점 여부가 엔진마다 달라"));
        assertTrue(props.path("status").path("description").asString().contains("flavor를 못 읽어"));
        assertTrue(node.path("overLimit").path("description").asString().contains("반올림 전"));
        assertTrue(node.path("overLimit").path("description").asString().contains("물리 총량이 0인 자원은 넘지 않은 것으로 본다"));
    }

    private JsonNode dryRun() throws Exception {
        String body = mockMvc.perform(post(DRY_RUN)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
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

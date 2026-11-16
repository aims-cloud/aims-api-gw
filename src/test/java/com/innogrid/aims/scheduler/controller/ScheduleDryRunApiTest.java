package com.innogrid.aims.scheduler.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;
import com.innogrid.aims.scheduler.service.ScheduleService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스케줄 모의 실행의 HTTP 계약 — 열일곱 키와 순서, 결정 여덟 키, 스냅샷 열두 칸, 쓰기 없음, 404·400 래퍼, 403, Swagger. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ScheduleDryRunApiTest {

    private static final String BASE = "/api/v1/scheduler/schedules";
    private static final String PREFIX = "sdra-";
    private static final List<String> KEYS = List.of("id", "scheduleId", "enabled", "resourceType", "policyName", "policySource",
            "engine", "engineSource", "status", "message", "emptyReason", "targetCount", "successCount", "failedCount", "truncated",
            "clusterSnapshot", "decisions");
    private static final List<String> DECISION_KEYS = List.of("workloadId", "workloadName", "success", "selectedMdcId",
            "selectedNodeId", "finalScore", "reason", "exclusionCodes");
    private static final List<String> SNAPSHOT_KEYS = List.of("status", "knobsDigest", "nodeCount", "excludedCount",
            "powerMeasuredNodes", "measuredPowerWatts", "activeNodesBefore", "activeNodesAfter", "reclaimableWattsBefore",
            "reclaimableWattsAfter", "avgTemperatureBefore", "avgTemperatureAfter");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @MockitoBean NodeResourceCollector collector;

    @AfterEach
    void clean() {
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith(PREFIX)).forEach(s -> scheduleService.delete(List.of(s.getId())));
    }

    @DisplayName("모의 실행은 열일곱 키·결정 여덟 키·스냅샷 열두 칸을 그 순서로 돌려주고 아무것도 쓰지 않는다")
    @Test
    void dry_run_returns_keys_in_order() throws Exception {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", PREFIX + "node:9100", 8, 65_536, 0, 10, 20, 0, 300, 500, 0, 0, 0)), List.of()));
        Workload w = new Workload(PREFIX + "w", 1, 64);
        w.setResourceType(Workload.ResourceType.CONTAINER);
        workloadRepository.saveAndFlush(w);
        long scheduleId = scheduleService.create(new ScheduleRequest(PREFIX + "s", PREFIX + "s", Workload.ResourceType.CONTAINER,
                "0 0 2 * * ?", false, true, PREFIX, "weighted", null, null, null, null)).id();
        long runs = runRepository.count();
        long history = historyRepository.count();

        String body = mockMvc.perform(post(BASE + "/" + scheduleId + "/dry-run"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).get("data");
        assertEquals(KEYS, names(data), "열일곱 키·순서");
        assertEquals(SNAPSHOT_KEYS, names(data.get("clusterSnapshot")), "실행 상세와 같은 열두 칸");
        assertEquals(1, data.get("decisions").size());
        assertEquals(DECISION_KEYS, names(data.get("decisions").get(0)), "결정 여덟 키·순서");
        assertEquals("COMPLETED", data.get("status").asString());
        assertTrue(data.get("emptyReason").isNull());
        assertEquals(1, data.get("successCount").asInt());
        assertEquals(runs, runRepository.count(), "실행 이력 없음");
        assertEquals(history, historyRepository.count(), "배치 이력 없음");
        assertEquals(Workload.WorkloadStatus.PENDING, workloadRepository.findById(w.getId()).orElseThrow().getStatus());
    }

    @DisplayName("없는 스케줄은 404, 숫자가 아닌 id 는 400 이며 둘 다 공통 래퍼다")
    @Test
    void unknown_404_and_bad_id_400_in_wrapper() throws Exception {
        mockMvc.perform(post(BASE + "/999999/dry-run"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(post(BASE + "/abc/dry-run"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }

    @DisplayName("미인증이면 403 이다")
    @Test
    void anonymous_is_403() throws Exception {
        mockMvc.perform(post(BASE + "/1/dry-run").with(anonymous())).andExpect(status().isForbidden());
    }

    @DisplayName("Swagger 에 경로가 있고 응답은 모의 실행 스키마를 가리키며 스냅샷 스키마는 실행 상세 것을 재사용한다")
    @Test
    void swagger_path_and_schemas() throws Exception {
        JsonNode doc = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode op = doc.get("paths").get(BASE + "/{id}/dry-run").get("post");
        assertEquals("모의 실행", op.get("summary").asString());
        String resp = op.get("responses").get("200").get("content").properties().iterator().next().getValue().get("schema").get("$ref").asString();
        assertTrue(resp.endsWith("/ApiResponseScheduleDryRunResponse"), resp);
        JsonNode schemas = doc.get("components").get("schemas");
        assertNotNull(schemas.get("ScheduleDryRunResponse"));
        assertNotNull(schemas.get("DryRunDecision"));
        assertTrue(schemas.get("ScheduleDryRunResponse").get("properties").get("clusterSnapshot").get("$ref").asString().endsWith("/ClusterSnapshot"));
        assertNotNull(doc.get("paths").get(BASE + "/{id}/run"), "기존 즉시 실행 경로 유지");
    }

    @DisplayName("Swagger 의 미리보기 · 모의 실행 스키마는 세 열거형 속성을 값 목록으로 직접 싣고 열거형 스키마를 따로 만들지 않는다")
    @Test
    void swagger_enum_properties_are_inline() throws Exception {
        JsonNode schemas = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("components").get("schemas");
        List<String> policySource = List.of("SCHEDULE", "RESOURCE_TYPE", "COMMON", "DEFAULT");
        List<String> engineSource = List.of("SCHEDULE", "POLICY", "DEFAULT");
        List<String> emptyReason = List.of("NONE", "WAITING", "EXHAUSTED");
        for (String schema : List.of("ScheduleRunPreviewResponse", "ScheduleDryRunResponse")) {
            JsonNode properties = schemas.get(schema).get("properties");
            assertInlineEnum(schema, properties.get("policySource"), policySource);
            assertInlineEnum(schema, properties.get("engineSource"), engineSource);
            assertInlineEnum(schema, properties.get("emptyReason"), emptyReason);
        }
        // 따로 만든 열거형 스키마는 이름이 아니라 값 목록으로 찾는다 — 짧은 이름은 다른 패키지의 같은 이름 클래스가 쓸 수 있다
        for (Map.Entry<String, JsonNode> entry : schemas.properties()) {
            assertFalse(List.of(policySource, engineSource, emptyReason).contains(enumValues(entry.getValue())),
                    "열거형 스키마가 따로 생기면 Swagger 가 바뀐다: " + entry.getKey());
        }
    }

    private static void assertInlineEnum(String schema, JsonNode property, List<String> values) {
        assertNotNull(property, schema);
        assertTrue(property.get("$ref") == null, schema + " 의 열거형 속성이 참조로 바뀌었다");
        assertEquals("string", property.get("type").asString(), schema);
        assertEquals(values, enumValues(property), schema);
    }

    private static List<String> enumValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        JsonNode array = node.get("enum");
        if (array != null) {
            array.forEach(v -> values.add(v.asString()));
        }
        return values;
    }

    private static List<String> names(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }
}

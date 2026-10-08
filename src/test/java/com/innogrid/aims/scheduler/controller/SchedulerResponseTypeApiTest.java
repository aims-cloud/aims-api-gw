package com.innogrid.aims.scheduler.controller;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스케줄러 응답 타입 명시의 계약 — 레코드로 바꾼 네 응답의 키 순서, 작업 10개의 응답 스키마 참조와 새 이름,
 * 기존 Point·Candidate 스키마 본문 보존, 스케줄러 컨트롤러 응답 선언에 와일드카드·원시 타입이 없음.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class SchedulerResponseTypeApiTest {

    private static final String BASE = "/api/v1/scheduler";
    private static final String PREFIX = "rst-";
    private static final String NODE = PREFIX + "node:9100";
    private static final String REF = "#/components/schemas/";

    /** 작업 10개와 그 200 응답 스키마 이름. */
    private static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("get /workloads/resource-types", "ApiResponseListResourceTypeOptionResponse"),
            Map.entry("post /workloads/resume-exhausted", "ApiResponseResumeExhaustedResponse"),
            Map.entry("post /workloads/{workloadId}/schedule", "ApiResponseSchedulingResult"),
            Map.entry("get /nodes/power-efficiency", "ApiResponsePowerEfficiencyResponse"),
            Map.entry("get /nodes/{nodeId}/power-efficiency/trend", "ApiResponsePowerEfficiencyTrendResponse"),
            Map.entry("get /constraints", "ApiResponseListConstraintDescriptionResponse"),
            Map.entry("get /thermal/cooldowns", "ApiResponseListThermalCooldownResponse"),
            Map.entry("get /engines", "ApiResponseListEngineResponse"),
            Map.entry("get /optimization/power", "ApiResponsePowerOptimizationResponse"),
            Map.entry("get /optimization/consolidation", "ApiResponseConsolidationResponse"));

    /** 타입을 명시하면서 공개한 스키마 28개(중첩은 응답 이름을 앞에 붙인 이름). */
    private static final Set<String> NEW_SCHEMAS = Set.of(
            "ApiResponseConsolidationResponse", "ApiResponseListConstraintDescriptionResponse", "ApiResponseListEngineResponse",
            "ApiResponseListResourceTypeOptionResponse", "ApiResponseListThermalCooldownResponse", "ApiResponsePowerEfficiencyResponse",
            "ApiResponsePowerEfficiencyTrendResponse", "ApiResponsePowerOptimizationResponse", "ApiResponseResumeExhaustedResponse",
            "ConsolidationCandidate", "ConsolidationMove", "ConsolidationResponse", "ConsolidationSummary", "ConstraintDescriptionResponse",
            "EngineResponse", "PowerEfficiencyNodeEfficiency", "PowerEfficiencyResponse", "PowerEfficiencySummary",
            "PowerEfficiencyTrendPoint", "PowerEfficiencyTrendResponse", "PowerOptimizationCluster", "PowerOptimizationFinding",
            "PowerOptimizationMdc", "PowerOptimizationResponse", "PowerOptimizationSummary",
            "ResourceTypeOptionResponse", "ResumeExhaustedResponse", "ThermalCooldownResponse");

    private static final Set<String> TEN = Set.of("getResourceTypes", "resumeExhaustedWorkloads", "scheduleWorkload",
            "getPowerEfficiency", "getPowerEfficiencyTrend", "getConstraints", "getThermalCooldowns", "getEngines",
            "getPowerOptimization", "getConsolidation");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ThermalModel thermalModel;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;
    @MockitoBean NodeResourceCollector collector;

    @BeforeEach
    void stubNodes() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource(PREFIX + "mdc", NODE, 8, 65_536, 0, 10, 20, 0, 100, 500, 36.5, 0, 0)), List.of()));
    }

    @AfterEach
    void clean() {
        thermalModel.clearCritical(NODE);
    }

    @DisplayName("레코드로 바꾼 네 응답은 키를 선언 순서로 낸다")
    @Test
    void map_replacements_keep_declared_key_order() throws Exception {
        String types = raw(get(BASE + "/workloads/resource-types"));
        for (String t : List.of("VM", "GPU", "NPU", "CONTAINER")) {
            assertTrue(types.contains("{\"code\":\"" + t + "\",\"label\":\"" + t + "\"}"), types);
        }

        JsonNode engines = objectMapper.readTree(raw(get(BASE + "/engines"))).get("data");
        assertEquals(3, engines.size());
        engines.forEach(e -> assertEquals(List.of("id", "name"), names(e)));

        assertEquals("{\"success\":true,\"data\":{\"resumedCount\":0,\"workloads\":[]}}",
                raw(post(BASE + "/workloads/resume-exhausted").param("resourceType", "NPU")
                        .param("from", "2000-01-01T00:00:00Z").param("to", "2000-01-01T00:00:01Z")));

        thermalModel.markCritical(NODE);
        JsonNode cooldowns = objectMapper.readTree(raw(get(BASE + "/thermal/cooldowns"))).get("data");
        assertEquals(1, cooldowns.size());
        assertEquals(List.of("mdcId", "nodeId", "temperatureCelsius", "thermalState", "remainingCooldownMinutes"), names(cooldowns.get(0)));
    }

    @DisplayName("작업 10개의 200 응답은 명시한 타입의 스키마를 가리킨다")
    @Test
    void ten_operations_reference_typed_schemas() throws Exception {
        JsonNode doc = apiDocs();
        for (Map.Entry<String, String> op : OPERATIONS.entrySet()) {
            String[] mp = op.getKey().split(" ", 2);
            JsonNode operation = doc.get("paths").get(BASE + mp[1]).get(mp[0]);
            assertNotNull(operation, op.getKey());
            String ref = operation.get("responses").get("200").get("content").properties().iterator().next().getValue()
                    .get("schema").get("$ref").asString();
            assertEquals(REF + op.getValue(), ref, op.getKey());
        }
    }

    @DisplayName("새 스키마 28개가 있고 Summary 는 없으며 기존 Point·Candidate 본문은 그대로다")
    @Test
    void schema_names_keep_existing_bodies() throws Exception {
        JsonNode schemas = apiDocs().get("components").get("schemas");
        for (String name : NEW_SCHEMAS) assertNotNull(schemas.get(name), name);
        assertNull(schemas.get("Summary"), "이름 없는 Summary 는 공개하지 않는다");

        assertTrue(has(schemas, "Point", "totalNodes") && !has(schemas, "Point", "peiRaw"), "기존 Point = 전력 최적화 추세 지점");
        assertTrue(has(schemas, "PowerEfficiencyTrendPoint", "peiRaw") && has(schemas, "PowerEfficiencyTrendPoint", "cpuUsagePercent"));
        assertTrue(has(schemas, "Candidate", "instance") && !has(schemas, "Candidate", "netSavingWatts"), "기존 Candidate = 익스포터 후보");
        assertTrue(has(schemas, "ConsolidationCandidate", "netSavingWatts"));
        assertTrue(has(schemas, "PowerEfficiencySummary", "averagePeiRaw"));
        assertTrue(has(schemas, "PowerOptimizationSummary", "findingCount"));
        assertTrue(has(schemas, "ConsolidationSummary", "reclaimableNodes"));
        assertTrue(has(schemas, "PowerOptimizationCluster", "reclaimableIdleWatts"));
        assertTrue(has(schemas, "PowerOptimizationMdc", "effectivePowerWatts"));
        assertTrue(has(schemas, "PowerOptimizationFinding", "estimatedSavingWatts"));
        assertTrue(has(schemas, "ConsolidationMove", "marginalWatts"));
        assertTrue(has(schemas, "PowerEfficiencyNodeEfficiency", "peiNormalized"));

        assertEquals(REF + "PowerEfficiencyTrendPoint",
                schemas.get("PowerEfficiencyTrendResponse").get("properties").get("points").get("items").get("$ref").asString());
        assertEquals(REF + "ConsolidationCandidate",
                schemas.get("ConsolidationResponse").get("properties").get("candidates").get("items").get("$ref").asString());
        assertEquals(REF + "ConsolidationSummary",
                schemas.get("ConsolidationResponse").get("properties").get("summary").get("$ref").asString());
    }

    @DisplayName("스케줄러 컨트롤러의 응답 선언에 와일드카드·타입 변수·원시 제네릭이 없다")
    @Test
    void scheduler_controllers_declare_no_wildcards() {
        Set<String> scanned = new TreeSet<>();
        List<String> violations = new ArrayList<>();
        mapping.getHandlerMethods().values().stream()
                .filter(h -> h.getBeanType().getPackageName().equals("com.innogrid.aims.scheduler.controller"))
                .forEach(h -> {
                    Method m = h.getMethod();
                    scanned.add(m.getName());
                    if (!concrete(m.getGenericReturnType())) {
                        violations.add(h.getBeanType().getSimpleName() + "." + m.getName() + " → " + m.getGenericReturnType().getTypeName());
                    }
                });
        assertTrue(scanned.containsAll(TEN), "대상 메서드를 모두 훑었다: " + scanned);
        assertEquals(List.of(), violations);
    }

    /** 와일드카드·타입 변수·원시 제네릭이 한 겹이라도 있으면 false. */
    private static boolean concrete(Type t) {
        if (t instanceof WildcardType || t instanceof TypeVariable<?>) return false;
        if (t instanceof ParameterizedType p) return Arrays.stream(p.getActualTypeArguments()).allMatch(SchedulerResponseTypeApiTest::concrete);
        if (t instanceof GenericArrayType g) return concrete(g.getGenericComponentType());
        if (t instanceof Class<?> c) return c.getTypeParameters().length == 0;
        return false;
    }

    private static boolean has(JsonNode schemas, String schema, String property) {
        JsonNode s = schemas.get(schema);
        assertNotNull(s, schema);
        return s.get("properties") != null && s.get("properties").get(property) != null;
    }

    private JsonNode apiDocs() throws Exception {
        return objectMapper.readTree(raw(get("/v3/api-docs")));
    }

    private String raw(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static List<String> names(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }
}

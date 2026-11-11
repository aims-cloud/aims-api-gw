package com.innogrid.aims.scheduler.controller;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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

import com.innogrid.aims.scheduler.dto.ConsolidationResponse;
import com.innogrid.aims.scheduler.dto.ExcludedNodeResponse;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyResponse;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyTrendResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스케줄러 응답 타입 명시의 계약 — 레코드로 바꾼 네 응답의 키 순서, 작업 10개의 응답 스키마 참조와 새 이름,
 * 기존 Point·Candidate 스키마 본문 보존, 스케줄러 컨트롤러 응답 선언에 와일드카드·원시 타입이 없음.
 * 진단 응답의 중첩 레코드는 끝의 Response 를 뺀 응답 이름을 앞에 붙인 이름으로 공개한다(단순 이름은 다른 영역이 쓸 수 있다).
 * 진단 응답 넷과 그 안에 실리는 레코드는 값이 비어도 선언한 키를 모두 순서대로 내야 한다 — 판단 근거가 없다는 사실도 정보다.
 * 스케줄러 경로의 모든 작업은 성공 응답 data 로 이름 있는 스키마나 기본 타입을 돌려주고, 스케줄러 작업이 닿는 스키마 이름은
 * 공용 래퍼 말고는 다른 작업이 닿는 이름과 겹치지 않아야 한다(겹치면 springdoc 이 한쪽 본문을 버린다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class SchedulerResponseTypeApiTest {

    private static final String BASE = "/api/v1/scheduler";
    private static final String PREFIX = "rst-";
    private static final String NODE = PREFIX + "node:9100";
    /** 냉각 대기를 거는 평가의 온도 상한(기본 정책 값). */
    private static final double LIMIT = 75.0;
    private static final String REF = "#/components/schemas/";
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");
    /** Excel 내보내기 응답의 미디어 형식 — 이 형식으로 200 을 선언한 작업은 래퍼 검사에서 뺀다. */
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** 양쪽이 함께 써도 되는 라이브러리 스키마. 공용 타입을 일부러 함께 쓰게 되면 여기에 더한다. */
    private static final Set<String> LIBRARY = Set.of("JsonNode");

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

    /** 타입을 명시하면서 공개한 스키마 28개(중첩은 끝의 Response 를 뺀 응답 이름을 앞에 붙인 이름). */
    private static final Set<String> NEW_SCHEMAS = Set.of(
            "ApiResponseConsolidationResponse", "ApiResponseListConstraintDescriptionResponse", "ApiResponseListEngineResponse",
            "ApiResponseListResourceTypeOptionResponse", "ApiResponseListThermalCooldownResponse", "ApiResponsePowerEfficiencyResponse",
            "ApiResponsePowerEfficiencyTrendResponse", "ApiResponsePowerOptimizationResponse", "ApiResponseResumeExhaustedResponse",
            "ConsolidationCandidate", "ConsolidationMove", "ConsolidationResponse", "ConsolidationSummary", "ConstraintDescriptionResponse",
            "EngineResponse", "PowerEfficiencyNodeEfficiency", "PowerEfficiencyResponse", "PowerEfficiencySummary",
            "PowerEfficiencyTrendPoint", "PowerEfficiencyTrendResponse", "PowerOptimizationCluster", "PowerOptimizationFinding",
            "PowerOptimizationMdc", "PowerOptimizationResponse", "PowerOptimizationSummary",
            "ResourceTypeOptionResponse", "ResumeExhaustedResponse", "ThermalCooldownResponse");

    /** 진단 넷과 그 안에 실리는 레코드의 JSON 키 — 선언 순서. 바꾸면 화면이 읽는 키가 바뀐다. */
    private static final Map<Class<?>, List<String>> JSON_KEYS = Map.ofEntries(
            Map.entry(PowerEfficiencyResponse.class, List.of("evaluatedAt", "summary", "nodes")),
            Map.entry(PowerEfficiencyResponse.Summary.class, List.of("totalNodes", "evaluableNodes", "averagePeiRaw", "minPeiRaw", "maxPeiRaw",
                    "totalPowerWatts", "totalCapacityWatts", "gradeAvailable", "gradeUnavailableReason")),
            Map.entry(PowerEfficiencyResponse.NodeEfficiency.class, List.of("mdcId", "nodeId", "evaluable", "cpuUsagePercent", "powerWatts",
                    "powerCapacityWatts", "idlePowerWatts", "headroomPercent", "peiRaw", "peiNormalized", "proportionality", "grade", "rank",
                    "powerSource", "recommendation", "totalCpu", "usedCoresPerKw", "coreRank")),
            Map.entry(PowerEfficiencyTrendResponse.class, List.of("nodeId", "from", "to", "step", "points", "message")),
            Map.entry(PowerEfficiencyTrendResponse.Point.class, List.of("at", "powerWatts", "cpuUsagePercent", "peiRaw")),
            Map.entry(PowerOptimizationResponse.class, List.of("evaluatedAt", "cluster", "mdcs", "findings", "summary")),
            Map.entry(PowerOptimizationResponse.Mdc.class, List.of("mdcId", "nodeCount", "measuredNodes", "totalPowerWatts", "effectivePowerWatts",
                    "budgetWatts", "remainingWatts", "overBudget")),
            Map.entry(PowerOptimizationResponse.Cluster.class, List.of("totalNodes", "activeNodes", "idleNodes", "totalPowerWatts",
                    "reclaimableIdleWatts", "measured", "unmeasuredReason", "excludedNodes", "excluded")),
            Map.entry(PowerOptimizationResponse.Finding.class, List.of("type", "severity", "mdcId", "nodeId", "title", "detail",
                    "estimatedSavingWatts", "action")),
            Map.entry(PowerOptimizationResponse.Summary.class, List.of("findingCount", "estimatedSavingWatts", "savingPercent")),
            Map.entry(ConsolidationResponse.class, List.of("evaluatedAt", "feasible", "candidates", "summary", "limitation")),
            Map.entry(ConsolidationResponse.Candidate.class, List.of("mdcId", "nodeId", "evaluated", "skipReason", "reclaimable", "basis",
                    "workloadCount", "idlePowerWatts", "relocationCostWatts", "netSavingWatts", "powerRatioToIdle", "moves", "blockedReason", "detail")),
            Map.entry(ConsolidationResponse.Move.class, List.of("workloadId", "workloadName", "toNodeId", "marginalWatts")),
            Map.entry(ConsolidationResponse.Summary.class, List.of("candidateCount", "reclaimableNodes", "reclaimableWatts", "savingPercent",
                    "truncated", "truncatedReason", "excludedNodes", "excluded")),
            Map.entry(ExcludedNodeResponse.class, List.of("mdcId", "nodeId", "reason")));
    /** 기본형 성분에 넣는 표본 값 — 여기 없는 기본형이 생기면 생성에서 소리 나게 실패한다. */
    private static final Map<Class<?>, Object> ZERO = Map.of(boolean.class, false, int.class, 0, long.class, 0L, double.class, 0.0);

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

        thermalModel.markCritical(NODE, LIMIT);
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

    @DisplayName("새 스키마 28개가 있고 기존 Point·Candidate 본문은 그대로다")
    @Test
    void schema_names_keep_existing_bodies() throws Exception {
        JsonNode schemas = apiDocs().get("components").get("schemas");
        for (String name : NEW_SCHEMAS) assertNotNull(schemas.get(name), name);

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

    @DisplayName("전력 효율·효율 추세·전력 최적화·통합 진단 응답의 중첩 스키마는 끝의 Response 를 뺀 응답 이름을 앞에 붙여 공개하고 부모가 그 이름을 가리킨다")
    @Test
    void nested_schemas_use_response_prefix() throws Exception {
        JsonNode schemas = apiDocs().get("components").get("schemas");
        List<String> named = new ArrayList<>();
        for (Class<?> outer : List.of(PowerEfficiencyResponse.class, PowerEfficiencyTrendResponse.class,
                PowerOptimizationResponse.class, ConsolidationResponse.class)) {
            String prefix = outer.getSimpleName().replaceFirst("Response$", "");
            for (Class<?> nested : outer.getDeclaredClasses()) {
                if (!nested.isRecord()) continue;
                List<String> fields = Arrays.stream(nested.getRecordComponents()).map(RecordComponent::getName).toList();
                String name = prefix + nested.getSimpleName();
                assertNotNull(schemas.get(name), name);
                assertEquals(fields, names(schemas.get(name).get("properties")), name + " 속성 = 레코드 성분 순서");
                // 같은 단순 이름은 다른 응답이 쓸 수 있으므로 부재가 아니라 「우리 본문이 아님」만 본다
                JsonNode plain = schemas.get(nested.getSimpleName());
                assertTrue(plain == null || plain.get("properties") == null || !fields.equals(names(plain.get("properties"))),
                        nested.getSimpleName() + " 는 단순 이름으로 공개하지 않는다");
                named.add(name);
            }
        }
        assertTrue(named.size() >= 10, "네 응답의 중첩 레코드를 훑었다: " + named);
        JsonNode opt = schemas.get("PowerOptimizationResponse").get("properties");
        assertEquals(REF + "PowerOptimizationCluster", opt.get("cluster").get("$ref").asString());
        assertEquals(REF + "PowerOptimizationMdc", opt.get("mdcs").get("items").get("$ref").asString());
        assertEquals(REF + "PowerOptimizationFinding", opt.get("findings").get("items").get("$ref").asString());
        assertEquals(REF + "ConsolidationMove",
                schemas.get("ConsolidationCandidate").get("properties").get("moves").get("items").get("$ref").asString());
        assertEquals(REF + "PowerEfficiencyNodeEfficiency",
                schemas.get("PowerEfficiencyResponse").get("properties").get("nodes").get("items").get("$ref").asString());
    }

    @DisplayName("전력 효율·효율 추세·전력 최적화·통합 진단 응답과 그 안의 레코드는 값이 비어도 선언한 키를 모두 순서대로 낸다")
    @Test
    void diagnostic_records_write_every_key() throws Exception {
        Set<Class<?>> types = new HashSet<>();
        for (Class<?> root : List.of(PowerEfficiencyResponse.class, PowerEfficiencyTrendResponse.class,
                PowerOptimizationResponse.class, ConsolidationResponse.class)) collect(root, types);
        assertEquals(JSON_KEYS.keySet(), types, "진단 응답에 실리는 레코드가 바뀌었다 — 의도한 변경이면 JSON_KEYS 를 고치고 화면 담당에 알린다");
        for (Class<?> type : types) {
            JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(blank(type)));
            assertEquals(JSON_KEYS.get(type), names(json), type.getName() + " — null·빈 목록이어도 키는 있어야 한다");
        }
    }

    /** 레코드 성분(목록·배열이면 원소까지)을 따라 닿는 레코드 타입을 모은다. */
    private static void collect(Class<?> type, Set<Class<?>> out) {
        if (!out.add(type)) return;
        for (RecordComponent c : type.getRecordComponents()) collect(c.getGenericType(), out);
    }

    private static void collect(Type t, Set<Class<?>> out) {
        if (t instanceof Class<?> k) {
            if (k.isArray()) collect(k.getComponentType(), out);
            else if (k.isRecord()) collect(k, out);
        } else if (t instanceof ParameterizedType p) {
            for (Type x : p.getActualTypeArguments()) collect(x, out);
        } else if (t instanceof GenericArrayType g) {
            collect(g.getGenericComponentType(), out);
        }
    }

    /** 정준 생성자에 0·false·null 을 넣은 표본 — 압축 생성자가 null 목록을 빈 목록으로 바꾼다. */
    private static Object blank(Class<?> type) throws ReflectiveOperationException {
        Class<?>[] params = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            if (params[i].isPrimitive() && !ZERO.containsKey(params[i])) {
                fail(type.getName() + "." + type.getRecordComponents()[i].getName() + " — 표본 값이 없는 기본형 " + params[i] + ": ZERO 에 더한다");
            }
            args[i] = ZERO.get(params[i]);
        }
        return type.getDeclaredConstructor(params).newInstance(args);
    }

    @DisplayName("스케줄러 경로의 모든 작업은 성공 응답 data 로 이름 있는 스키마나 기본 타입을 돌려준다")
    @Test
    void scheduler_operations_return_typed_data() throws Exception {
        JsonNode doc = apiDocs();
        JsonNode schemas = doc.get("components").get("schemas");
        int checked = 0;
        List<String> loose = new ArrayList<>();
        List<String> files = new ArrayList<>();
        for (Entry<String, JsonNode> path : doc.get("paths").properties()) {
            if (!scheduler(path.getKey())) continue;
            for (Entry<String, JsonNode> op : path.getValue().properties()) {
                if (!METHODS.contains(op.getKey())) continue;
                checked++;
                JsonNode content = op.getValue().path("responses").path("200").path("content");
                if (content.has(XLSX)) {   // 본문이 파일인 작업은 래퍼가 없다 — 정확히 내보내기 두 경로뿐인지 아래에서 본다
                    files.add(op.getKey() + " " + path.getKey() + " " + content.get(XLSX).path("schema").path("format").asString());
                    continue;
                }
                JsonNode ref = content.size() > 0 ? content.properties().iterator().next().getValue().path("schema").get("$ref") : null;
                String name = ref == null ? null : ref.asString().substring(REF.length());
                if ("ApiResponseVoid".equals(name)) continue;   // 본문 없는 작업의 래퍼
                JsonNode data = name == null ? null : schemas.path(name).path("properties").get("data");
                if (data == null || !typed(data)) loose.add(op.getKey() + " " + path.getKey() + " → " + name);
            }
        }
        assertTrue(checked >= 75, "스케줄러 작업을 모두 훑었다: " + checked + " — 작업을 일부러 줄였으면 하한을 고친다");
        assertEquals(List.of(), loose, "data 가 정체 없는 object 다 — 반환형을 레코드·기본 타입으로 명시한다");
        assertEquals(List.of("get /api/v1/scheduler/schedules/export binary", "get /api/v1/scheduler/schedules/{id}/runs/export binary"),
                files.stream().sorted().toList(), "본문이 xlsx 파일인 작업은 Excel 내보내기 두 경로뿐이다");
    }

    @DisplayName("스케줄러 작업과 다른 작업은 공용 래퍼 말고는 같은 스키마 이름에 닿지 않고 스케줄러는 이름 없는 Summary 를 쓰지 않는다")
    @Test
    void scheduler_schemas_stay_apart_from_other_operations() throws Exception {
        JsonNode doc = apiDocs();
        JsonNode schemas = doc.get("components").get("schemas");
        Set<String> ours = new TreeSet<>();
        Set<String> theirs = new TreeSet<>();
        for (Entry<String, JsonNode> path : doc.get("paths").properties()) seeds(path.getValue(), scheduler(path.getKey()) ? ours : theirs);
        reach(schemas, ours);
        reach(schemas, theirs);
        assertTrue(ours.size() >= 100 && theirs.size() >= 100, "양쪽 도달 집합이 비지 않았다: " + ours.size() + " / " + theirs.size());
        assertFalse(ours.contains("Summary"), "스케줄러 작업이 이름 없는 Summary 에 닿는다 — 중첩 Summary 에 @Schema(name) 을 붙인다");

        Set<String> shared = new TreeSet<>(ours);
        shared.retainAll(theirs);
        shared.removeIf(n -> LIBRARY.contains(n) || (n.startsWith("ApiResponse") && wrapsLibraryOrNothing(schemas.path(n))));
        if (!shared.isEmpty()) {
            StringBuilder why = new StringBuilder("스케줄러와 다른 API 가 같은 스키마 이름을 씁니다.");
            for (String n : shared) {
                why.append("\n- ").append(n).append(" ← 스케줄러 ").append(users(doc, schemas, n, true))
                        .append(" / 다른 작업 ").append(users(doc, schemas, n, false));
            }
            why.append("\n해소: 이름이 우연히 같으면 새로 만든 쪽 레코드에 @Schema(name = \"<영역 접두사><이름>\") 을 붙입니다.")
                    .append("\n같은 타입을 일부러 함께 쓰는 것이면(라이브러리·공용 타입) SchedulerResponseTypeApiTest.LIBRARY 에 이름을 더합니다.");
            fail(why.toString());
        }
    }

    /** data 가 없거나 이름 없는 object 이거나 라이브러리 스키마를 가리키는 래퍼 — 양쪽이 함께 써도 겹침이 아니다. */
    private static boolean wrapsLibraryOrNothing(JsonNode wrapper) {
        JsonNode ref = wrapper.path("properties").path("data").get("$ref");
        return ref == null || LIBRARY.contains(ref.asString().substring(REF.length()));
    }

    private static boolean scheduler(String path) {
        return path.startsWith(BASE + "/");
    }

    /** data 가 이름 있는 스키마·기본 타입·그 배열·값 타입이 정해진 맵이면 true. 정체 없는 object 는 false. */
    private static boolean typed(JsonNode data) {
        if (data.has("$ref")) return true;
        String type = data.path("type").asString();
        if (Set.of("string", "integer", "number", "boolean").contains(type)) return true;
        if ("array".equals(type)) return typed(data.path("items"));
        return "object".equals(type) && data.has("additionalProperties") && typed(data.get("additionalProperties"));
    }

    /** 경로 항목의 요청·매개변수·성공 응답이 가리키는 스키마. 오류 응답의 공용 본문은 겹침으로 세지 않는다. */
    private static void seeds(JsonNode pathItem, Set<String> out) {
        for (Entry<String, JsonNode> op : pathItem.properties()) {
            if (METHODS.contains(op.getKey())) seedsOf(op.getValue(), out); else refs(op.getValue(), out);
        }
    }

    /** 작업 하나의 요청·매개변수·2xx 응답이 가리키는 스키마. */
    private static void seedsOf(JsonNode operation, Set<String> out) {
        for (Entry<String, JsonNode> part : operation.properties()) {
            if (!part.getKey().equals("responses")) { refs(part.getValue(), out); continue; }
            for (Entry<String, JsonNode> r : part.getValue().properties()) if (r.getKey().startsWith("2")) refs(r.getValue(), out);
        }
    }

    /** 노드 아래의 모든 $ref 스키마 이름. */
    private static void refs(JsonNode node, Set<String> out) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.asString().startsWith(REF)) out.add(ref.asString().substring(REF.length()));
            node.properties().forEach(e -> refs(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(n -> refs(n, out));
        }
    }

    /** 씨앗에서 스키마 참조를 따라 닿는 이름을 모두 더한다. */
    private static void reach(JsonNode schemas, Set<String> set) {
        Deque<String> todo = new ArrayDeque<>(set);
        while (!todo.isEmpty()) {
            Set<String> next = new TreeSet<>();
            refs(schemas.path(todo.pop()), next);
            for (String n : next) if (set.add(n)) todo.push(n);
        }
    }

    /** 이름 n 에 닿는 작업(최대 3개) — 실패 메시지용. */
    private static List<String> users(JsonNode doc, JsonNode schemas, String n, boolean ours) {
        List<String> found = new ArrayList<>();
        for (Entry<String, JsonNode> path : doc.get("paths").properties()) {
            if (scheduler(path.getKey()) != ours) continue;
            for (Entry<String, JsonNode> op : path.getValue().properties()) {
                if (!METHODS.contains(op.getKey()) || found.size() >= 3) continue;
                Set<String> set = new TreeSet<>();
                seedsOf(op.getValue(), set);
                reach(schemas, set);
                if (set.contains(n)) found.add(op.getKey() + " " + path.getKey());
            }
        }
        return found;
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

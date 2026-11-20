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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.NodePlacementHoldRepository;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.PlacementInputRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /scheduler/policies/{policyId}/dry-run}의 HTTP 동작 — 키 순서, CPU 사용률 상한 1 로 고친 쪽만 빠짐, 안쪽 본문의
 * 명시적 null(비움), 오류 순서(404 → 본문 → 정책 값 → 초안 → 엔진), 정책 행과 표 행 수 그대로, operationId, 스키마 이름.
 *
 * <p>수용 개수 모의 배치 테스트와 같이 수집기만 목 객체로 바꾼다. 정책은 저장소로 비활성 행을 만들고 이름 접두어로 지운다 — 활성
 * 정책을 만들지 않아 다른 테스트의 배치 정책 선택을 바꾸지 않는다. 쓰기 0 · 정책 선택 규칙은 단위 테스트가 맡는다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PolicyEditPreviewApiTest {

    private static final String BASE = "/api/v1/scheduler";
    private static final String NODE = "ped:9100";
    private static final List<String> KEYS = List.of("evaluatedAt", "policyId", "policyName", "policyInUse",
            "resourceType", "changedFields", "currentEngine", "current", "editedEngine", "edited", "decisionChanged",
            "limitation");
    private static final List<String> RESULT_KEYS = List.of("success", "selectedMdcId", "selectedNodeId", "finalScore",
            "reason", "candidateScores", "policyName");
    private static final String WORKLOAD = "{\"name\":\"ped-draft\",\"requiredCpu\":1,\"requiredMemoryMb\":256}";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SchedulingPolicyRepository policyRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired NodePlacementHoldRepository holdRepository;
    @Autowired PlacementInputRepository inputRepository;
    @MockitoBean NodeResourceCollector collector;

    @AfterEach
    void clean() {
        policyRepository.findAll().stream().filter(p -> p.getName().startsWith("ped-"))
                .forEach(policyRepository::delete);
    }

    @DisplayName("수정이 없으면 키 순서대로 두 쪽이 같은 결과이고, 비활성 정책이라 policyInUse 는 false 다")
    @Test
    void no_changes_in_key_order() throws Exception {
        node();
        long id = policy("ped-same", null);

        JsonNode d = dryRun(id, "{\"changes\":{},\"workload\":" + WORKLOAD + "}", null);

        assertEquals(KEYS, names(d));
        assertEquals(id, d.get("policyId").asLong());
        assertEquals("ped-same", d.get("policyName").asString());
        assertFalse(d.get("policyInUse").asBoolean());
        assertEquals("VM", d.get("resourceType").asString());
        assertEquals(0, d.get("changedFields").size());
        assertEquals(RESULT_KEYS, names(d.get("current")));
        assertEquals(RESULT_KEYS, names(d.get("edited")));
        assertTrue(d.get("current").get("success").asBoolean());
        assertEquals(NODE, d.get("edited").get("selectedNodeId").asString());
        assertFalse(d.get("decisionChanged").asBoolean());
        assertTrue(d.get("evaluatedAt").asString().endsWith("Z"), d.get("evaluatedAt").asString());
        assertFalse(d.get("limitation").asString().isBlank());
    }

    @DisplayName("CPU 사용률 상한을 1 로 고치면 고친 쪽만 CPU_USAGE_EXCEEDED 로 빠진다")
    @Test
    void cpu_limit_one_excludes_only_edited() throws Exception {
        node();
        long id = policy("ped-cpu", null);

        JsonNode d = dryRun(id, "{\"changes\":{\"maxCpuUsagePercent\":1},\"workload\":" + WORKLOAD + "}", null);

        assertTrue(d.get("current").get("success").asBoolean());
        assertFalse(d.get("edited").get("success").asBoolean());
        assertEquals("CPU_USAGE_EXCEEDED",
                d.get("edited").get("candidateScores").get(0).get("exclusionCode").asString());
        assertTrue(d.get("decisionChanged").asBoolean());
        assertEquals("maxCpuUsagePercent", d.get("changedFields").get(0).asString());
        assertEquals(1, d.get("changedFields").size());
    }

    @DisplayName("안쪽 본문의 명시적 null 은 PUT 처럼 비움이다 — 기본 엔진 power 를 null 로 고치면 고친 쪽만 weighted")
    @Test
    void explicit_null_in_changes_clears_like_put() throws Exception {
        node();
        long id = policy("ped-null", "power");

        JsonNode d = dryRun(id, "{\"changes\":{\"defaultEngine\":null},\"workload\":" + WORKLOAD + "}", null);
        assertEquals("power", d.get("currentEngine").asString());
        assertEquals("weighted", d.get("editedEngine").asString());
        assertEquals("defaultEngine", d.get("changedFields").get(0).asString());

        JsonNode kept = dryRun(id, "{\"changes\":{},\"workload\":" + WORKLOAD + "}", null);
        assertEquals("power", kept.get("editedEngine").asString(), "대조: 키가 없으면 그대로");
    }

    @DisplayName("오류 순서 — 없는 정책 404 → 본문 없음 400 → 정책 값 400(PUT 과 같은 문구) → 초안 400(생성과 같은 문구) → 엔진 400")
    @Test
    void error_order() throws Exception {
        node();
        long id = policy("ped-err", null);
        String badChanges = "{\"cpuWeight\":-1}";
        String badWorkload = "{\"name\":\"ped-bad\",\"requiredCpu\":0,\"requiredMemoryMb\":256}";
        String putMessage = message(put(BASE + "/policies/" + id), badChanges, 400);
        String createMessage = message(post(BASE + "/workloads"), badWorkload, 400);

        mockMvc.perform(post(BASE + "/policies/999999999/dry-run")).andExpect(status().isNotFound());
        assertEquals("요청 본문이 필요합니다", message(post(BASE + "/policies/" + id + "/dry-run"), null, 400));
        assertEquals(putMessage, message(post(BASE + "/policies/" + id + "/dry-run"),
                "{\"changes\":" + badChanges + ",\"workload\":" + badWorkload + "}", 400), "정책 값이 초안보다 먼저");
        assertEquals("workload 는 필수입니다", message(post(BASE + "/policies/" + id + "/dry-run"), "{\"changes\":{}}", 400));
        assertEquals(createMessage, message(post(BASE + "/policies/" + id + "/dry-run").param("engine", "nope"),
                "{\"workload\":" + badWorkload + "}", 400), "초안이 엔진보다 먼저");
        assertEquals("알 수 없는 스케줄링 엔진입니다: nope",
                message(post(BASE + "/policies/" + id + "/dry-run").param("engine", "nope"),
                        "{\"workload\":" + WORKLOAD + "}", 400));
    }

    @DisplayName("부르기 전후로 정책 응답과 워크로드 · 배치 이력 · 전달 기록 · 정책 · 배치 중지 · 배치 입력 행 수가 그대로다")
    @Test
    void preview_changes_no_rows() throws Exception {
        node();
        long id = policy("ped-rows", null);
        String before = mockMvc.perform(get(BASE + "/policies/" + id)).andReturn().getResponse().getContentAsString();
        List<Long> counts = counts();

        dryRun(id, "{\"changes\":{\"maxCpuUsagePercent\":1,\"active\":true,\"name\":\"ped-renamed\"},"
                + "\"workload\":" + WORKLOAD + "}", null);

        assertEquals(before,
                mockMvc.perform(get(BASE + "/policies/" + id)).andReturn().getResponse().getContentAsString());
        assertEquals(counts, counts());
    }

    @DisplayName("새 작업의 operationId 는 번호 없는 메서드 이름이다")
    @Test
    void operation_id_is_unsuffixed_method_name() throws Exception {
        assertEquals("previewPolicyEdit",
                apiDocs().get("paths").get(BASE + "/policies/{policyId}/dry-run").get("post").get("operationId")
                        .asString());
    }

    @DisplayName("요청 · 응답 스키마는 선언 순 속성이고 결과는 기존 SchedulingResult 를 가리킨다")
    @Test
    void schemas_in_declared_order() throws Exception {
        JsonNode schemas = apiDocs().get("components").get("schemas");

        assertEquals(KEYS, names(schemas.get("PolicyDryRunResponse").get("properties")));
        assertEquals("#/components/schemas/SchedulingResult",
                schemas.get("PolicyDryRunResponse").get("properties").get("current").get("$ref").asString());
        assertEquals("#/components/schemas/SchedulingResult",
                schemas.get("PolicyDryRunResponse").get("properties").get("edited").get("$ref").asString());
        JsonNode request = schemas.get("PolicyDryRunRequest").get("properties");
        assertEquals(List.of("changes", "workload"), names(request));
        assertEquals("#/components/schemas/SchedulingPolicyRequest", request.get("changes").get("$ref").asString());
        assertEquals("#/components/schemas/WorkloadRequest", request.get("workload").get("$ref").asString());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** 사용률 10% · 64코어 노드 한 대. 호출마다 새 노드 객체. */
    private void node() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", NODE, 64, 65536, 0, 10, 20, 0, 0, 0, 0, 0, 0)), List.of()));
    }

    /** 비활성 정책 행(기본값 · 기본 엔진만 지정). */
    private long policy(String name, String defaultEngine) {
        SchedulingPolicy p = new SchedulingPolicy(name, "d");
        p.setActive(false);
        p.setDefaultEngine(defaultEngine);
        return policyRepository.save(p).getId();
    }

    private JsonNode dryRun(long id, String body, String engine) throws Exception {
        MockHttpServletRequestBuilder req = post(BASE + "/policies/" + id + "/dry-run")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (engine != null) req = req.param("engine", engine);
        return data(mockMvc.perform(req).andExpect(status().isOk()).andReturn());
    }

    private String message(MockHttpServletRequestBuilder request, String body, int expectedStatus) throws Exception {
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult r = mockMvc.perform(request).andReturn();
        assertEquals(expectedStatus, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("message").asString();
    }

    private List<Long> counts() {
        return List.of(workloadRepository.count(), historyRepository.count(), dispatchRepository.count(),
                policyRepository.count(), holdRepository.count(), inputRepository.count());
    }

    private JsonNode apiDocs() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
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

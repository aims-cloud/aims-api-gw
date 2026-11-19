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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /scheduler/workloads/dry-run/capacity}의 HTTP 동작 — 키 순서, GPU 로 멈추는 개수, 기존 예약 반영, 엔진 매개변수,
 * 400(엔진 · 본문 검증), 아무것도 쓰지 않음, operationId, 중첩 스키마 이름.
 *
 * <p>노드 비우기 모의 배치 테스트와 같이 수집기만 목 객체로 바꾼다. 개수는 사용률 상한에 걸리지 않는 GPU 로 정한다 — 노드 사용률 0% 에
 * 코어가 넉넉해 정책 상한과 관계없이 GPU 장수에서 멈춘다. 쌓기 · 사용률 · 배치 중지 같은 규칙은 {@code DraftCapacityPreviewerTest}가
 * 맡는다. 롤백이 없어 이름 접두어로 정리한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class DraftCapacityApiTest {

    private static final String BASE = "/api/v1/scheduler";
    private static final String PATH = BASE + "/workloads/dry-run/capacity";
    private static final String NODE = "cap:9100";
    private static final List<String> KEYS = List.of("evaluatedAt", "resourceType", "policyName", "engine", "nodeCount",
            "excludedNodeCount", "placeableCount", "limit", "truncated", "stopReason", "stopExclusionCodes", "nodes",
            "limitation");
    private static final List<String> NODE_KEYS = List.of("mdcId", "nodeId", "placedCount", "stopReason", "stopCode");
    private static final String GPU_DRAFT =
            "{\"name\":\"cap-draft\",\"requiredCpu\":1,\"requiredMemoryMb\":256,\"requiredGpu\":1}";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired SchedulingPolicyRepository policyRepository;
    @Autowired NodePlacementHoldRepository holdRepository;
    @Autowired PlacementInputRepository inputRepository;
    @MockitoBean NodeResourceCollector collector;

    @AfterEach
    void clean() {
        dispatchRepository.findAll().stream()
                .filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith("cap-"))
                .forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream()
                .filter(w -> w.getName().startsWith("cap-"))
                .forEach(workloadRepository::delete);
    }

    @DisplayName("키 순서대로, GPU 두 장 노드에 GPU 하나 초안이 두 개 들어가고 셋째 시도의 자원 부족으로 멈춘다")
    @Test
    void gpu_node_takes_two_in_key_order() throws Exception {
        gpuNode();

        JsonNode d = capacity(post(PATH));

        assertEquals(KEYS, names(d));
        assertEquals("VM", d.get("resourceType").asString());
        assertEquals("weighted", d.get("engine").asString());
        assertFalse(d.get("policyName").isNull());
        assertEquals(1, d.get("nodeCount").asInt());
        assertEquals(0, d.get("excludedNodeCount").asInt());
        assertEquals(2, d.get("placeableCount").asInt());
        assertEquals(500, d.get("limit").asInt());
        assertFalse(d.get("truncated").asBoolean());
        assertEquals("{\"INSUFFICIENT_RESOURCE\":1}", d.get("stopExclusionCodes").asString());
        JsonNode row = d.get("nodes").get(0);
        assertEquals(NODE_KEYS, names(row));
        assertEquals("mdc-01", row.get("mdcId").asString());
        assertEquals(NODE, row.get("nodeId").asString());
        assertEquals(2, row.get("placedCount").asInt());
        assertEquals("INSUFFICIENT_RESOURCE", row.get("stopCode").asString());
        assertTrue(row.get("stopReason").asString().startsWith("자원 부족"), row.get("stopReason").asString());
        assertTrue(d.get("evaluatedAt").asString().endsWith("Z"), d.get("evaluatedAt").asString());
        assertFalse(d.get("limitation").asString().isBlank());
    }

    @DisplayName("GPU 를 이미 잡은 배치 대기가 있으면 하나만 더 들어간다")
    @Test
    void existing_reservation_is_counted() throws Exception {
        gpuNode();
        held("cap-held");

        assertEquals(1, capacity(post(PATH)).get("placeableCount").asInt());
    }

    @DisplayName("engine 을 주면 그 엔진으로 세고 개수는 같으며, 모르는 엔진은 400 이다")
    @Test
    void requested_engine_and_bad_engine() throws Exception {
        gpuNode();

        JsonNode power = capacity(post(PATH).param("engine", "power"));
        assertEquals("power", power.get("engine").asString());
        assertEquals(2, power.get("placeableCount").asInt());

        mockMvc.perform(post(PATH).param("engine", "nope").contentType(MediaType.APPLICATION_JSON).content(GPU_DRAFT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("알 수 없는 스케줄링 엔진입니다: nope"));
    }

    @DisplayName("본문 검증은 워크로드 생성과 같은 400 · 같은 문구다")
    @Test
    void body_validation_matches_create() throws Exception {
        gpuNode();
        String bad = "{\"name\":\"cap-bad\",\"requiredCpu\":0,\"requiredMemoryMb\":256}";

        String created = badRequestMessage(post(BASE + "/workloads"), bad);
        String counted = badRequestMessage(post(PATH), bad);

        assertEquals(created, counted);
        assertTrue(counted.contains("요구 CPU"), counted);
        assertEquals(created, badRequestMessage(post(PATH).param("engine", "nope"), bad), "본문 검증이 엔진 검사보다 먼저다");
    }

    @DisplayName("부르기 전후로 워크로드 · 배치 이력 · 전달 기록 · 정책 · 배치 중지 · 배치 입력 행 수와 기존 배치 대기가 그대로다")
    @Test
    void capacity_changes_no_rows() throws Exception {
        gpuNode();
        long wid = held("cap-still");
        // 정책 행 수는 held()의 단건 배치가 정책을 정한(없으면 default 를 저장한) 뒤에 세므로 default 저장 여부는
        // 가르지 못한다 — 그것은 DraftCapacityPreviewerTest.saves_nothing 이 본다
        List<Long> before = counts();

        assertEquals(1, capacity(post(PATH)).get("placeableCount").asInt());

        assertEquals(before, counts());
        assertEquals("SCHEDULED", workloadRepository.findById(wid).orElseThrow().getStatus().name());
        assertEquals(NODE, workloadRepository.findById(wid).orElseThrow().getAssignedNodeId());
    }

    @DisplayName("새 작업의 operationId 는 번호 없는 메서드 이름이다")
    @Test
    void operation_id_is_unsuffixed_method_name() throws Exception {
        JsonNode paths = apiDocs().get("paths");

        assertEquals("previewDraftCapacity", paths.get(PATH).get("post").get("operationId").asString());
    }

    @DisplayName("응답 스키마는 응답 이름을 앞에 붙인 중첩 이름으로 공개하고 nodes 가 그 이름을 가리킨다")
    @Test
    void nested_schema_uses_response_prefix() throws Exception {
        JsonNode schemas = apiDocs().get("components").get("schemas");

        assertEquals(KEYS, names(schemas.get("DraftCapacityResponse").get("properties")), "속성 = 레코드 성분 순서");
        assertEquals("#/components/schemas/DraftCapacityNode", schemas.get("DraftCapacityResponse")
                .get("properties").get("nodes").get("items").get("$ref").asString());
        assertEquals(NODE_KEYS, names(schemas.get("DraftCapacityNode").get("properties")));
        // 단순 이름 Node는 다른 영역의 응답이 이미 쓴다 — 있어도 되고, 속성이 이 노드 행과 같지만 않으면 된다
        JsonNode plain = schemas.get("Node");
        assertTrue(plain == null || !NODE_KEYS.equals(names(plain.get("properties"))), "단순 이름으로 공개하지 않는다");
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** 사용률 0% · 64코어 · GPU 2장 노드 한 대. 호출마다 새 노드 객체. */
    private void gpuNode() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", NODE, 64, 65536, 2, 0, 0, 0, 0, 0, 0, 0, 0)), List.of()));
    }

    /** GPU 하나를 요구하는 워크로드를 만들고 단건 스케줄링으로 그 노드의 배치 대기로 만든다(기본 record 모드 → ACCEPTED). */
    private long held(String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"requiredCpu\":1,\"requiredMemoryMb\":256,\"requiredGpu\":1}";
        long wid = data(mockMvc.perform(post(BASE + "/workloads").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn()).get("id").asLong();
        mockMvc.perform(post(BASE + "/workloads/" + wid + "/schedule")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.selectedNodeId").value(NODE));
        return wid;
    }

    private JsonNode capacity(MockHttpServletRequestBuilder request) throws Exception {
        return data(mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(GPU_DRAFT))
                .andExpect(status().isOk()).andReturn());
    }

    private String badRequestMessage(MockHttpServletRequestBuilder request, String body) throws Exception {
        String json = mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("message").asString();
    }

    private JsonNode apiDocs() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<Long> counts() {
        return List.of(workloadRepository.count(), historyRepository.count(), dispatchRepository.count(),
                policyRepository.count(), holdRepository.count(), inputRepository.count());
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

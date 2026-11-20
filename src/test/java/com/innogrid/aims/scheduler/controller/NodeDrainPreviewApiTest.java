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

import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.NodePlacementHoldRepository;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
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
 * {@code GET /scheduler/nodes/{nodeId}/drain-preview}의 HTTP 동작 — 키 순서, 콜론이 든 노드 id, 노드 한 대일 때의 실패와 두 대일 때의
 * 성공, 엔진 매개변수, 400 · 404, 아무것도 쓰지 않음, operationId.
 *
 * <p>노드별 예약 내역 테스트와 같이 수집기만 목 객체로 바꾸고 단건 스케줄링 API로 배치 대기 행을 만든다(기본 record 모드 →
 * ACCEPTED). 누적 · 순서 · 종류별 정책 같은 규칙은 {@code NodeDrainPreviewerTest}가 맡는다. 롤백이 없어 이름 접두어로 정리한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class NodeDrainPreviewApiTest {

    private static final String BASE = "/api/v1/scheduler";
    private static final String NODE = "drn:9100";
    private static final String OTHER = "drn-b:9100";
    private static final List<String> KEYS = List.of("evaluatedAt", "nodeId", "collected", "held", "holdReason",
            "otherNodeCount", "workloadCount", "placeableCount", "truncated", "allPlaceable", "decisions",
            "limitation");
    private static final List<String> DECISION_KEYS = List.of("workloadId", "workloadName", "status", "judgment",
            "resourceType", "policyName", "engine", "success", "selectedMdcId", "selectedNodeId", "finalScore",
            "reason", "exclusionCodes");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired SchedulingPolicyRepository policyRepository;
    @Autowired NodePlacementHoldRepository holdRepository;
    @MockitoBean NodeResourceCollector collector;

    @AfterEach
    void clean() {
        dispatchRepository.findAll().stream()
                .filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith("drn-"))
                .forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream()
                .filter(w -> w.getName().startsWith("drn-"))
                .forEach(workloadRepository::delete);
    }

    @DisplayName("노드가 한 대뿐이면 키 순서대로 그 노드의 배치 대기를 옮길 곳 없음으로 돌려준다")
    @Test
    void single_node_has_nowhere_to_go() throws Exception {
        long wid = placed("drn-one", 3, 2048);

        JsonNode d = preview(NODE);

        assertEquals(KEYS, names(d));
        assertEquals(NODE, d.get("nodeId").asString());
        assertTrue(d.get("collected").asBoolean());
        assertFalse(d.get("held").asBoolean());
        assertTrue(d.get("holdReason").isNull());
        assertEquals(0, d.get("otherNodeCount").asInt());
        assertEquals(1, d.get("workloadCount").asInt());
        assertEquals(0, d.get("placeableCount").asInt());
        assertFalse(d.get("allPlaceable").asBoolean());
        JsonNode row = d.get("decisions").get(0);
        assertEquals(DECISION_KEYS, names(row));
        assertEquals(wid, row.get("workloadId").asLong());
        assertEquals("SCHEDULED", row.get("status").asString());
        assertEquals("FULL", row.get("judgment").asString());
        assertEquals("VM", row.get("resourceType").asString());
        assertFalse(row.get("success").asBoolean());
        assertEquals("옮길 다른 노드가 없습니다", row.get("reason").asString());
        assertTrue(row.get("selectedNodeId").isNull());
        assertFalse(d.get("limitation").asString().isBlank());
    }

    @DisplayName("노드가 두 대면 다른 노드로 옮기고, engine 을 주면 그 엔진으로 놓는다")
    @Test
    void second_node_receives_with_requested_engine() throws Exception {
        placed("drn-two", 2, 1024);
        twoNodes();

        JsonNode row = preview(NODE).get("decisions").get(0);
        assertTrue(row.get("success").asBoolean(), row.get("reason").asString());
        assertEquals(OTHER, row.get("selectedNodeId").asString());

        JsonNode power = data(mockMvc.perform(get(BASE + "/nodes/" + NODE + "/drain-preview").param("engine", "power"))
                .andExpect(status().isOk()).andReturn()).get("decisions").get(0);
        assertEquals("power", power.get("engine").asString());
    }

    @DisplayName("모르는 엔진은 400, 어디에도 없는 노드는 404 다")
    @Test
    void bad_engine_is_400_and_unknown_node_is_404() throws Exception {
        twoNodes();
        mockMvc.perform(get(BASE + "/nodes/" + NODE + "/drain-preview").param("engine", "nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get(BASE + "/nodes/drn-ghost:9100/drain-preview"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("노드를 찾을 수 없습니다: drn-ghost:9100"));
    }

    @DisplayName("부르기 전후로 워크로드 · 배치 이력 · 전달 기록 · 정책 · 배치 중지 행 수와 워크로드 상태가 그대로다")
    @Test
    void preview_changes_no_rows() throws Exception {
        long wid = placed("drn-still", 1, 256);
        twoNodes();
        List<Long> before = counts();
        String status = workloadRepository.findById(wid).orElseThrow().getStatus().name();

        preview(NODE);

        assertEquals(before, counts());
        assertEquals(status, workloadRepository.findById(wid).orElseThrow().getStatus().name());
        assertEquals(NODE, workloadRepository.findById(wid).orElseThrow().getAssignedNodeId());
    }

    @DisplayName("새 작업의 operationId 는 번호 없는 메서드 이름이다")
    @Test
    void operation_id_is_unsuffixed_method_name() throws Exception {
        JsonNode paths = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("paths");

        assertEquals("previewNodeDrain",
                paths.get(BASE + "/nodes/{nodeId}/drain-preview").get("get").get("operationId").asString());
    }

    @DisplayName("응답 스키마는 응답 이름을 앞에 붙인 중첩 이름으로 공개하고 decisions 가 그 이름을 가리킨다")
    @Test
    void nested_schema_uses_response_prefix() throws Exception {
        JsonNode schemas = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("components").get("schemas");

        assertEquals("#/components/schemas/NodeDrainPreviewDecision", schemas.get("NodeDrainPreviewResponse")
                .get("properties").get("decisions").get("items").get("$ref").asString());
        assertEquals(DECISION_KEYS, names(schemas.get("NodeDrainPreviewDecision").get("properties")), "속성 = 레코드 성분 순서");
        // 단순 이름 Decision은 실행 상세의 결정이 쓴다 — 있어도 되고, 속성이 이 결정 행과 같지만 않으면 된다
        JsonNode plain = schemas.get("Decision");
        assertTrue(plain == null || !DECISION_KEYS.equals(names(plain.get("properties"))), "단순 이름으로 공개하지 않는다");
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** 노드 1대(16코어)를 주고 워크로드를 만든 뒤 단건 스케줄링으로 배치 대기로 만든다. */
    private long placed(String name, int cpu, int memoryMb) throws Exception {
        // 호출마다 새 노드 객체 — 예약은 노드 객체에 쌓이므로 같은 객체를 돌려주면 예약이 쌓여 보인다
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", NODE, 16, 65536, 0, 10, 20, 0, 0, 500, 0, 0, 0)), List.of()));
        String body = "{\"name\":\"" + name + "\",\"requiredCpu\":" + cpu + ",\"requiredMemoryMb\":" + memoryMb + "}";
        JsonNode w = data(mockMvc.perform(post(BASE + "/workloads")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        long wid = w.get("id").asLong();
        mockMvc.perform(post(BASE + "/workloads/" + wid + "/schedule")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.selectedNodeId").value(NODE));
        return wid;
    }

    /** 비울 노드와 여유로운 다른 노드 한 대. */
    private void twoNodes() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(
                new NodeResource("mdc-01", NODE, 16, 65536, 0, 10, 20, 0, 0, 500, 0, 0, 0),
                new NodeResource("mdc-01", OTHER, 32, 131072, 0, 5, 10, 0, 0, 500, 0, 0, 0)), List.of()));
    }

    private JsonNode preview(String nodeId) throws Exception {
        return data(mockMvc.perform(get(BASE + "/nodes/" + nodeId + "/drain-preview"))
                .andExpect(status().isOk()).andReturn());
    }

    private List<Long> counts() {
        return List.of(workloadRepository.count(), historyRepository.count(), dispatchRepository.count(),
                policyRepository.count(), holdRepository.count());
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

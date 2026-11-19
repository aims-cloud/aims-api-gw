package com.innogrid.aims.scheduler.controller;

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
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 노드 배치 중지가 HTTP 배치 경로에 걸리는지 — 원장 API 로 등록하면 단건 모의 배치가 그 노드를 빼고, 해제하면 다시 고른다.
 *
 * <p>수집기 하나만 가짜로 바꾼 기존 테스트들과 같은 구성이라 같은 스프링 컨텍스트를 쓴다. 노드 둘 가운데 여유가 큰 노드를
 * 중지해, 중지하지 않은 같은 입력(대조)에서는 그 노드가 골라진다는 것을 함께 본다. 모의 배치라 배치 이력을 남기지 않는다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class NodePlacementHoldPlacementApiTest {

    private static final String BASE = "/api/v1/scheduler";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NodePlacementHoldRepository holdRepository;
    @Autowired WorkloadRepository workloadRepository;
    @MockitoBean NodeResourceCollector collector;

    @AfterEach
    void clean() {
        holdRepository.findAll().stream().filter(h -> h.getNodeId().startsWith("nhp-")).forEach(holdRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith("nhp-"))
                .forEach(workloadRepository::delete);
    }

    @DisplayName("중지한 노드는 모의 배치 후보에서 배치 중지 코드로 빠지고 해제하면 다시 골라진다")
    @Test
    void hold_excludes_node_until_released() throws Exception {
        twoNodes();
        long wid = data(mockMvc.perform(post(BASE + "/workloads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"nhp-w\",\"requiredCpu\":2,\"requiredMemoryMb\":512}"))
                .andExpect(status().isOk()).andReturn())
                .get("id").asLong();

        assertEquals("nhp-big:9100", dryRun(wid).get("selectedNodeId").asString(), "대조: 중지 전에는 여유가 큰 노드를 고른다");

        long holdId = data(mockMvc.perform(post(BASE + "/node-placement-holds").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nodeId\":\"nhp-big:9100\",\"reason\":\"점검\"}")).andExpect(status().isOk()).andReturn())
                .get("id").asLong();
        JsonNode held = dryRun(wid);
        assertEquals("nhp-small:9100", held.get("selectedNodeId").asString());
        JsonNode big = candidate(held, "nhp-big:9100");
        assertFalse(big.get("eligible").asBoolean());
        assertEquals("NODE_PLACEMENT_HOLD", big.get("exclusionCode").asString());
        assertEquals("운영자 배치 중지 (사유: 점검)", big.get("exclusionReason").asString());

        mockMvc.perform(delete(BASE + "/node-placement-holds/" + holdId)).andExpect(status().isOk());
        assertEquals("nhp-big:9100", dryRun(wid).get("selectedNodeId").asString(), "해제하면 다시 후보다");
    }

    @DisplayName("제약 설명에 C7 노드 배치 중지가 C0 다음 자리로 나온다")
    @Test
    void constraints_list_c7_after_c0() throws Exception {
        JsonNode list = data(mockMvc.perform(get(BASE + "/constraints")).andExpect(status().isOk()).andReturn());

        assertEquals(List.of("C0", "C7", "C1", "C2", "C3", "C4", "C5", "C6"),
                java.util.stream.IntStream.range(0, list.size())
                        .mapToObj(i -> list.get(i).get("code").asString()).toList());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private void twoNodes() {
        // 호출마다 새 노드 객체 — 예약은 노드 객체에 누적되므로 같은 객체를 돌려주면 예약이 쌓여 보인다
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(
                new NodeResource("mdc-01", "nhp-big:9100", 64, 131072, 0, 5, 10, 0, 0, 500, 0, 0, 0),
                new NodeResource("mdc-01", "nhp-small:9100", 8, 16384, 0, 40, 40, 0, 0, 500, 0, 0, 0)), List.of()));
    }

    private JsonNode dryRun(long workloadId) throws Exception {
        return data(mockMvc.perform(post(BASE + "/workloads/" + workloadId + "/schedule").param("dryRun", "true"))
                .andExpect(status().isOk()).andReturn());
    }

    private static JsonNode candidate(JsonNode result, String nodeId) {
        for (JsonNode c : result.get("candidateScores")) {
            if (nodeId.equals(c.get("nodeId").asString())) return c;
        }
        throw new AssertionError("후보에 없는 노드: " + nodeId);
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("data");
    }
}

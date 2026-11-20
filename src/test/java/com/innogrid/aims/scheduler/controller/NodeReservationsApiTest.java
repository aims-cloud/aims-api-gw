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
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
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
 * {@code GET /scheduler/nodes/reservations}의 HTTP 동작 — 리터럴 경로, 키 순서, 노드 필터, 노드 목록의 예약과 같은 합계.
 *
 * <p>수집기를 목 객체로 바꿔 노드 1대를 주고 단건 스케줄링 API로 배치 대기 행을 만든다(기본 record 모드 → ACCEPTED).
 * 노드 목록과 이 조회는 호출마다 판정 시각이 달라 합계 대조는 시각 경계에서 먼 행(방금 배치한 배치 대기)으로만 한다 —
 * 같은 시각의 대조는 {@code NodeReservationReaderTest}가 맡는다. 롤백이 없어 이름 접두어로 정리한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class NodeReservationsApiTest {

    private static final String BASE = "/api/v1/scheduler";
    private static final String NODE = "rsv:9100";
    private static final List<String> SUMS =
            List.of("reservedCpu", "reservedMemoryMb", "reservedGpu", "reservedDiskGb");
    private static final List<String> KEYS =
            List.of("evaluatedAt", "reservationTtlHours", "metricLagMinutes", "unassignedCount", "nodes");
    private static final List<String> NODE_KEYS =
            List.of("nodeId", "reservedCpu", "reservedMemoryMb", "reservedGpu", "reservedDiskGb", "workloads");
    private static final List<String> ROW_KEYS = List.of("workloadId", "name", "status", "judgment", "scheduledAt",
            "startedAt", "reservedCpu", "reservedMemoryMb", "reservedGpu", "reservedDiskGb");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @MockitoBean NodeResourceCollector collector;

    @AfterEach
    void clean() {
        dispatchRepository.findAll().stream()
                .filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith("rsv-"))
                .forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream()
                .filter(w -> w.getName().startsWith("rsv-"))
                .forEach(workloadRepository::delete);
    }

    @DisplayName("예약 내역은 리터럴 경로로 200 이고 노드 단건 경로에 잡히지 않는다")
    @Test
    void literal_path_is_not_node_lookup() throws Exception {
        mockMvc.perform(get(BASE + "/nodes/reservations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nodes").isArray());
    }

    @DisplayName("응답 키는 선언 순서이고 규칙 값은 설정 기본값이다")
    @Test
    void keys_in_declared_order() throws Exception {
        JsonNode d = data(mockMvc.perform(get(BASE + "/nodes/reservations")).andExpect(status().isOk()).andReturn());

        assertEquals(KEYS, names(d));
        assertEquals(24, d.get("reservationTtlHours").asInt());
        assertEquals(10, d.get("metricLagMinutes").asInt());
    }

    @DisplayName("배치한 워크로드는 그 노드 아래 FULL 행으로 나오고 합계가 노드 목록의 예약과 같다")
    @Test
    void placed_workload_matches_node_reservation() throws Exception {
        long wid = placed("rsv-one", 3, 2048);

        JsonNode node = data(mockMvc.perform(get(BASE + "/nodes/reservations").param("nodeId", " " + NODE + " "))
                .andExpect(status().isOk()).andReturn()).get("nodes");
        assertEquals(1, node.size());
        JsonNode n = node.get(0);
        assertEquals(NODE_KEYS, names(n));
        assertEquals(NODE, n.get("nodeId").asString());
        assertEquals(1, n.get("workloads").size());
        JsonNode row = n.get("workloads").get(0);
        assertEquals(ROW_KEYS, names(row));
        assertEquals(wid, row.get("workloadId").asLong());
        assertEquals("SCHEDULED", row.get("status").asString());
        assertEquals("FULL", row.get("judgment").asString());
        assertFalse(row.get("scheduledAt").isNull());
        assertTrue(row.get("startedAt").isNull(), "배치 대기는 시작 시각이 없다");
        assertEquals(List.of(3L, 2048L, 0L, 0L), SUMS.stream().map(k -> row.get(k).asLong()).toList());

        JsonNode listed = data(mockMvc.perform(get(BASE + "/nodes/" + NODE)).andExpect(status().isOk()).andReturn());
        for (String k : SUMS) {
            assertEquals(listed.get(k).asLong(), n.get(k).asLong(), k);
        }
    }

    @DisplayName("없는 노드를 지정하면 200 빈 배열이다")
    @Test
    void unknown_node_is_empty() throws Exception {
        placed("rsv-other", 1, 64);

        mockMvc.perform(get(BASE + "/nodes/reservations").param("nodeId", "no-such-node"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes").isEmpty());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** 노드 1대(16코어)를 주고 워크로드를 만든 뒤 단건 스케줄링으로 배치 대기로 만든다. */
    private long placed(String name, int cpu, int memoryMb) throws Exception {
        // 호출마다 새 노드 객체 — 예약은 노드 객체에 누적되므로 같은 객체를 돌려주면 예약이 쌓여 보인다
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", NODE, 16, 65536, 0, 10, 20, 0, 0, 500, 0, 0, 0)), List.of()));
        String body = "{\"name\":\"" + name + "\",\"requiredCpu\":" + cpu + ",\"requiredMemoryMb\":" + memoryMb + "}";
        JsonNode w = data(mockMvc.perform(post(BASE + "/workloads")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        long wid = w.get("id").asLong();
        mockMvc.perform(post(BASE + "/workloads/" + wid + "/schedule")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));
        return wid;
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

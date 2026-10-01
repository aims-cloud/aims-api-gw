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

import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
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
 * 배치 예약 취소({@code POST /workloads/{id}/release})의 HTTP 계약 — 키 4개, 상태·배정·전달 기록의 뒤처리, 409·404, 늦은 콜백,
 * 삭제 가능, 상태 변경 API 의 되돌리기 400 회귀.
 *
 * <p>수집기를 대역으로 바꿔 노드 1대(16코어)를 주고 단건 스케줄링 API 로 결정을 만든다(기본 record 모드 → ACCEPTED).
 * 이 시험은 트랜잭션을 걸지 않아 롤백이 없다 — 이름 접두어로 정리한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class WorkloadReleaseApiTest {

    private static final String BASE = "/api/v1/scheduler";
    private static final List<String> KEYS = List.of("workload", "releasedMdcId", "releasedNodeId", "dispatch");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @MockitoBean NodeResourceCollector collector;

    @AfterEach
    void clean() {
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith("rel-")).forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith("rel-")).forEach(workloadRepository::delete);   // RUNNING 도 저장소 직접 삭제라 409 없음
    }

    private void bigNode() {
        // 호출마다 새 노드 객체 — 예약은 노드 객체에 누적되므로 같은 객체를 돌려주면 예약이 쌓여 보인다
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", "big:9100", 16, 65536, 0, 10, 20, 0, 0, 500, 0, 0, 0)), List.of()));
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("data");
    }

    /** 워크로드 생성 → 단건 스케줄링 성공 → [workloadId, dispatchId]. */
    private long[] placed(String name) throws Exception {
        bigNode();
        JsonNode w = data(mockMvc.perform(post(BASE + "/workloads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"requiredCpu\":1,\"requiredMemoryMb\":64}")).andExpect(status().isOk()).andReturn());
        long wid = w.get("id").asLong();
        mockMvc.perform(post(BASE + "/workloads/" + wid + "/schedule")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));
        JsonNode list = data(mockMvc.perform(get(BASE + "/workloads/" + wid + "/dispatches")).andExpect(status().isOk()).andReturn());
        assertEquals(1, list.size());
        return new long[] {wid, list.get(0).get("id").asLong()};
    }

    @DisplayName("취소는 200 에 키 4개 순서로 응답하고 워크로드는 PENDING·배정 null, 전달 기록은 FAILED 로 마감된다")
    @Test
    void release_returns_four_keys_pending_and_closed_dispatch() throws Exception {
        long[] ids = placed("rel-ok");
        long histories = historyRepository.count();

        JsonNode d = data(mockMvc.perform(post(BASE + "/workloads/" + ids[0] + "/release")).andExpect(status().isOk()).andReturn());

        List<String> names = new ArrayList<>();
        d.propertyNames().forEach(names::add);
        assertEquals(KEYS, names, "키 집합과 순서");
        assertEquals("PENDING", d.get("workload").get("status").asString());
        assertTrue(d.get("workload").get("assignedNodeId").isNull());
        assertTrue(d.get("workload").get("assignedMdcId").isNull());
        assertTrue(d.get("workload").get("schedulingScore").isNull());
        assertEquals(0, d.get("workload").get("retryCount").asInt(), "실패가 아니라 재시도 횟수 불변");
        assertTrue(d.get("workload").get("schedulingReason").asString().startsWith("예약 취소"));
        assertEquals("mdc-01", d.get("releasedMdcId").asString());
        assertEquals("big:9100", d.get("releasedNodeId").asString());
        assertEquals(ids[1], d.get("dispatch").get("id").asLong());
        assertEquals("FAILED", d.get("dispatch").get("status").asString());
        assertTrue(d.get("dispatch").get("message").asString().contains("예약 취소"));
        assertFalse(d.get("dispatch").get("respondedAt").isNull());
        assertEquals(histories, historyRepository.count(), "배치 이력은 새로 쓰지 않는다");

        Workload w = workloadRepository.findById(ids[0]).orElseThrow();
        assertEquals(Workload.WorkloadStatus.PENDING, w.getStatus());
        assertNotNull(w.getScheduledAt(), "추적용 배정 시각은 남긴다");
        assertTrue(d.get("dispatch").get("message").asString().startsWith("예약 취소 — "), "표지가 앞");
    }

    @DisplayName("취소하면 노드 예약이 곧바로 빠진다 — 배치 뒤 reservedCpu 1 이 취소 뒤 0 으로")
    @Test
    void release_frees_node_reservation_immediately() throws Exception {
        long[] ids = placed("rel-resv");
        JsonNode before = data(mockMvc.perform(get(BASE + "/nodes")).andExpect(status().isOk()).andReturn());
        int reservedBefore = before.get(0).get("reservedCpu").asInt();
        assertTrue(reservedBefore >= 1, "배치된 워크로드가 예약으로 잡힌다");

        mockMvc.perform(post(BASE + "/workloads/" + ids[0] + "/release")).andExpect(status().isOk());

        JsonNode after = data(mockMvc.perform(get(BASE + "/nodes")).andExpect(status().isOk()).andReturn());
        assertEquals(reservedBefore - 1, after.get(0).get("reservedCpu").asInt(), "취소된 워크로드의 예약 1코어가 빠진다");
    }

    @DisplayName("없는 워크로드는 404, 취소를 다시 부르면 409, PENDING·FAILED·RUNNING 워크로드도 409 이고 RUNNING 은 전용 문안이다")
    @Test
    void release_404_and_409_rules() throws Exception {
        mockMvc.perform(post(BASE + "/workloads/999999999/release")).andExpect(status().isNotFound());

        long[] ids = placed("rel-twice");
        mockMvc.perform(post(BASE + "/workloads/" + ids[0] + "/release")).andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/workloads/" + ids[0] + "/release")).andExpect(status().isConflict());   // PENDING

        long[] failed = placed("rel-failed");
        mockMvc.perform(patch(BASE + "/workloads/" + failed[0] + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FAILED\"}")).andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/workloads/" + failed[0] + "/release")).andExpect(status().isConflict());

        long[] running = placed("rel-running");
        mockMvc.perform(patch(BASE + "/workloads/" + running[0] + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RUNNING\"}")).andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/workloads/" + running[0] + "/release")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("실행 중")));
    }

    @DisplayName("취소 뒤 늦게 온 콜백 RUNNING 은 409, FAILED 는 200 무변경이고 워크로드는 PENDING 으로 남는다")
    @Test
    void late_callbacks_after_release() throws Exception {
        long[] ids = placed("rel-late");
        mockMvc.perform(post(BASE + "/workloads/" + ids[0] + "/release")).andExpect(status().isOk());
        String url = BASE + "/dispatches/" + ids[1] + "/result";

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RUNNING\"}")).andExpect(status().isConflict());
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}")).andExpect(status().isConflict());
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FAILED\",\"message\":\"늦은 보고\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"));

        Workload w = workloadRepository.findById(ids[0]).orElseThrow();
        assertEquals(Workload.WorkloadStatus.PENDING, w.getStatus(), "늦은 실패 보고가 대기 워크로드를 실패로 바꾸지 않는다");
        assertEquals(0, w.resolveRetryCount());
        PlacementDispatch d = dispatchRepository.findById(ids[1]).orElseThrow();
        assertTrue(d.getMessage().contains("예약 취소"), "무변경 — 늦은 보고의 메시지로 덮이지 않는다");
    }

    @DisplayName("취소한 워크로드는 삭제할 수 있고 전달 기록은 함께 지워지지만 배치 이력은 남는다")
    @Test
    void released_workload_can_be_deleted() throws Exception {
        long[] ids = placed("rel-del");
        long histories = historyRepository.count();
        mockMvc.perform(delete(BASE + "/workloads/" + ids[0])).andExpect(status().isConflict());   // SCHEDULED 는 409

        mockMvc.perform(post(BASE + "/workloads/" + ids[0] + "/release")).andExpect(status().isOk());
        mockMvc.perform(delete(BASE + "/workloads/" + ids[0])).andExpect(status().isOk());

        assertTrue(dispatchRepository.findById(ids[1]).isEmpty(), "전달 기록은 워크로드와 함께 지워진다");
        assertEquals(histories, historyRepository.count(), "배치 이력은 연구 근거라 남는다");
    }

    @DisplayName("상태 변경 API 로 SCHEDULED 를 PENDING 으로 되돌리는 것은 여전히 400 이다")
    @Test
    void patch_scheduled_to_pending_still_400() throws Exception {
        long[] ids = placed("rel-patch");
        mockMvc.perform(patch(BASE + "/workloads/" + ids[0] + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PENDING\"}")).andExpect(status().isBadRequest());
        assertEquals(Workload.WorkloadStatus.SCHEDULED, workloadRepository.findById(ids[0]).orElseThrow().getStatus());
    }
}

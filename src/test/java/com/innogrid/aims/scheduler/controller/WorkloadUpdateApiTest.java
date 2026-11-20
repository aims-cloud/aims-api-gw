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

import com.innogrid.aims.scheduler.entity.Workload;
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
 * 워크로드 편집({@code PUT /workloads/{workloadId}})의 HTTP 동작 — 응답 23키, 상세 조회 왕복 무변경,
 * 편집 대상이 아닌 키 무시, 상태 409와 예약 취소 뒤 편집, null 비우기, 오류 래퍼.
 *
 * <p>트랜잭션을 걸지 않아 롤백이 없다 — 이름 접두어 {@code edit-api-}로 골라 지운다. 배치가 필요한 테스트만
 * 수집기 목 객체로 노드 1대(16코어)를 준다(호출마다 새 노드 객체 — 같은 객체를 돌려주면 예약이 누적돼 보인다).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class WorkloadUpdateApiTest {

    private static final String BASE = "/api/v1/scheduler";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @MockitoBean NodeResourceCollector collector;

    @AfterEach
    void clean() {
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith("edit-api-")).forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith("edit-api-")).forEach(workloadRepository::delete);
    }

    private void bigNode() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", "big:9100", 16, 65536, 0, 10, 20, 0, 0, 500, 0, 0, 0)), List.of()));
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("data");
    }

    private long create(String name) throws Exception {
        JsonNode w = data(mockMvc.perform(post(BASE + "/workloads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"requiredCpu\":1,\"requiredMemoryMb\":64,\"requiredBandwidthMbps\":1000,"
                        + "\"dataMdcId\":\"mdc-01\",\"description\":\"원본\"}")).andExpect(status().isOk()).andReturn());
        return w.get("id").asLong();
    }

    /** 열세 필드를 모두 채운 워크로드 — 왕복 테스트가 필드마다 실제 값을 되돌려 보내게 한다. */
    private long createFull(String name) throws Exception {
        JsonNode w = data(mockMvc.perform(post(BASE + "/workloads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"description\":\"원본\",\"requiredCpu\":2,\"requiredMemoryMb\":128,"
                        + "\"requiredGpu\":1,\"requiredDiskGb\":5,\"resourceType\":\"GPU\",\"dataMdcId\":\"mdc-01\","
                        + "\"preferredMdcId\":\"mdc-02\",\"maxPowerBudgetWatts\":300,\"maxTemperatureCelsius\":70,"
                        + "\"requiredBandwidthMbps\":1000,\"priority\":\"HIGH\"}")).andExpect(status().isOk()).andReturn());
        return w.get("id").asLong();
    }

    private JsonNode fetch(long id) throws Exception {
        return data(mockMvc.perform(get(BASE + "/workloads/" + id)).andExpect(status().isOk()).andReturn());
    }

    private MvcResult edit(long id, String json) throws Exception {
        return mockMvc.perform(put(BASE + "/workloads/" + id).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private static List<String> keys(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    @DisplayName("편집 응답은 상세 조회와 같은 23키이고 값도 다음 조회와 같다")
    @Test
    void put_returns_same_23_keys_as_get() throws Exception {
        long id = create("edit-api-keys");
        List<String> before = keys(fetch(id));

        JsonNode edited = data(mockMvc.perform(put(BASE + "/workloads/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"edited\",\"requiredCpu\":2}")).andExpect(status().isOk()).andReturn());

        assertEquals(23, before.size());
        assertEquals(before, keys(edited), "키 집합과 순서");
        assertEquals(edited, fetch(id), "응답 값 = 다음 조회 값");
        assertEquals("edited", edited.get("description").asString());
        assertEquals(2, edited.get("requiredCpu").asInt());
    }

    @DisplayName("열세 칸을 채운 상세 조회 응답을 그대로 보내면 아무것도 바뀌지 않는다")
    @Test
    void get_body_round_trip_changes_nothing() throws Exception {
        long id = createFull("edit-api-roundtrip");
        JsonNode before = fetch(id);
        for (String key : List.of("name", "description", "requiredCpu", "requiredMemoryMb", "requiredGpu", "requiredDiskGb",
                "resourceType", "dataMdcId", "preferredMdcId", "maxPowerBudgetWatts", "maxTemperatureCelsius",
                "requiredBandwidthMbps", "priority")) {
            assertFalse(before.get(key).isNull(), "표본이 값을 가져야 왕복이 의미 있다: " + key);
        }

        assertEquals(200, edit(id, objectMapper.writeValueAsString(before)).getResponse().getStatus());

        assertEquals(before, fetch(id));
    }

    @DisplayName("상태·재시도·배정 키를 보내도 무시된다")
    @Test
    void status_and_retry_keys_in_body_are_ignored() throws Exception {
        long id = create("edit-api-ignore");

        mockMvc.perform(put(BASE + "/workloads/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\",\"retryCount\":9,\"assignedNodeId\":\"x\",\"schedulingScore\":1.0}"))
                .andExpect(status().isOk());

        JsonNode after = fetch(id);
        assertEquals("PENDING", after.get("status").asString());
        assertTrue(after.get("assignedNodeId").isNull());
        assertTrue(after.get("schedulingScore").isNull());
        assertEquals(0, after.get("retryCount").asInt());
    }

    @DisplayName("배치된 워크로드는 409 이고 예약 취소 뒤에는 편집된다")
    @Test
    void scheduled_409_then_release_then_edit_ok() throws Exception {
        bigNode();
        long id = create("edit-api-scheduled");
        mockMvc.perform(post(BASE + "/workloads/" + id + "/schedule")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));

        mockMvc.perform(put(BASE + "/workloads/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"requiredCpu\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("/release")));
        mockMvc.perform(post(BASE + "/workloads/" + id + "/release")).andExpect(status().isOk());
        mockMvc.perform(put(BASE + "/workloads/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"requiredCpu\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requiredCpu").value(2));
    }

    @DisplayName("실행 중·완료 워크로드는 409 다")
    @Test
    void running_and_completed_409() throws Exception {
        long running = create("edit-api-running");
        Workload r = workloadRepository.findById(running).orElseThrow();
        r.assignTo("mdc-01", "n1", 0.5, "배치");
        r.transitionTo(Workload.WorkloadStatus.RUNNING);
        workloadRepository.saveAndFlush(r);
        assertEquals(409, edit(running, "{\"requiredCpu\":2}").getResponse().getStatus());

        long done = create("edit-api-done");
        Workload c = workloadRepository.findById(done).orElseThrow();
        c.assignTo("mdc-01", "n1", 0.5, "배치");
        c.transitionTo(Workload.WorkloadStatus.RUNNING);
        c.transitionTo(Workload.WorkloadStatus.COMPLETED);
        workloadRepository.saveAndFlush(c);
        assertEquals(409, edit(done, "{\"requiredCpu\":2}").getResponse().getStatus());
        assertEquals(1, fetch(done).get("requiredCpu").asInt());
    }

    @DisplayName("HTTP 로 요구 대역폭에 null 을 보내면 비워진다")
    @Test
    void null_clears_required_bandwidth_over_http() throws Exception {
        long id = create("edit-api-null");

        JsonNode edited = data(mockMvc.perform(put(BASE + "/workloads/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requiredBandwidthMbps\":null}")).andExpect(status().isOk()).andReturn());

        assertTrue(edited.get("requiredBandwidthMbps").isNull());
        assertEquals("mdc-01", edited.get("dataMdcId").asString(), "보내지 않은 칸은 유지");
    }

    @DisplayName("404·400 은 공통 오류 래퍼로 사유를 돌려준다")
    @Test
    void errors_404_400_keep_wrapper() throws Exception {
        mockMvc.perform(put(BASE + "/workloads/999999999").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("워크로드를 찾을 수 없습니다: 999999999"));

        long id = create("edit-api-errors");
        mockMvc.perform(put(BASE + "/workloads/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"requiredCpu\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("요구 CPU은(는) 1 이상이어야 합니다: 0"));
        mockMvc.perform(put(BASE + "/workloads/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"requiredBandwidthMbps\":\"100\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("요구 대역폭(Mbps)은(는) 숫자여야 합니다"));
        mockMvc.perform(put(BASE + "/workloads/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requiredBandwidthMbps\":1" + "0".repeat(310) + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("요구 대역폭(Mbps)은(는) 유한한 0 보다 큰 수여야 합니다: Infinity"));
        mockMvc.perform(put(BASE + "/workloads/" + id))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("요청 본문이 필요합니다"));
    }

    @DisplayName("본문을 읽지 못한 400 은 워크로드를 찾기 전에 난다 — 없는 id 에도 400")
    @Test
    void unreadable_body_400_before_lookup() throws Exception {
        mockMvc.perform(put(BASE + "/workloads/999999999").contentType(MediaType.APPLICATION_JSON).content("{\"requiredCpu\":"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("요청 본문을 읽을 수 없습니다")));
        mockMvc.perform(put(BASE + "/workloads/999999999").contentType(MediaType.APPLICATION_JSON).content("{\"requiredCpu\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("요청 본문을 읽을 수 없습니다")));
    }
}

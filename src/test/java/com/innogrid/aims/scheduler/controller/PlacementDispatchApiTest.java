package com.innogrid.aims.scheduler.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 디스패치 API 4종의 HTTP 계약 — 필드 13개, 콜백 상태 코드, 페이지 필터, 워크로드 삭제 시 정리, 인증.
 *
 * <p>수집기를 대역으로 바꿔 노드 1대(16코어)를 주고 단건 스케줄링 API 로 결정을 만든다. 기본(record) 모드라 디스패치는
 * ACCEPTED 로 시작한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class PlacementDispatchApiTest {

    static final Set<String> FIELDS = new TreeSet<>(List.of("id", "workloadId", "workloadName", "scheduleRunId", "mdcId", "nodeId",
            "status", "idempotencyKey", "externalRef", "message", "requestedAt", "respondedAt", "createdBy"));

    private static final String BASE = "/api/v1/scheduler";
    /** 실재 실행과 겹치지 않는 예약 대역의 실행 id — 동률 페이지 테스트 전용. */
    private static final long RUN_Z = 9_900_880L;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @MockitoBean NodeResourceCollector collector;

    @AfterEach
    void clean() {
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith("dpapi-")).forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith("dpapi-")).forEach(workloadRepository::delete);
    }

    private void bigNode() {
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", "big:9100", 16, 65536, 0, 10, 20, 0, 0, 500, 0, 0, 0)), List.of()));
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("data");
    }

    /** 워크로드 생성 → 단건 스케줄링 → 디스패치 1건. 반환은 [workloadId, dispatchId]. */
    private long[] placed(String name) throws Exception {
        JsonNode w = data(mockMvc.perform(post(BASE + "/workloads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"requiredCpu\":1,\"requiredMemoryMb\":64}")).andExpect(status().isOk()).andReturn());
        long wid = w.get("id").asLong();
        mockMvc.perform(post(BASE + "/workloads/" + wid + "/schedule")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.policyName").isString());   // 응답 JSON 에 적용 정책 이름이 실린다
        JsonNode list = data(mockMvc.perform(get(BASE + "/workloads/" + wid + "/dispatches")).andExpect(status().isOk()).andReturn());
        assertEquals(1, list.size());
        return new long[] {wid, list.get(0).get("id").asLong()};
    }

    @DisplayName("배치 직후 워크로드의 디스패치는 1건 ACCEPTED 이고 응답은 13필드, 호출자 계정이 남는다")
    @Test
    void dispatch_after_placement_13_fields() throws Exception {
        bigNode();
        long[] ids = placed("dpapi-fields");
        JsonNode d = data(mockMvc.perform(get(BASE + "/dispatches/" + ids[1])).andExpect(status().isOk()).andReturn());
        assertEquals(FIELDS, new TreeSet<>(d.propertyNames()));
        assertEquals("ACCEPTED", d.get("status").asString());
        assertEquals(ids[0], d.get("workloadId").asLong());
        assertEquals("big:9100", d.get("nodeId").asString());
        assertEquals("user", d.get("createdBy").asString());
        assertTrue(d.get("scheduleRunId").isNull(), "단건 API 결정은 실행 id 가 없다");
        assertTrue(d.get("externalRef").isNull());
        assertFalse(d.get("respondedAt").isNull());
    }

    @DisplayName("콜백 — running 200 → 워크로드 RUNNING, 재보고 200, COMPLETED 200, 이후 FAILED 409, 모르는 값 400, 본문 없음 400, 없는 id 404")
    @Test
    void callback_status_codes() throws Exception {
        bigNode();
        long[] ids = placed("dpapi-cb");
        String url = BASE + "/dispatches/" + ids[1] + "/result";

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"running\",\"externalRef\":\"job-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.externalRef").value("job-1"));
        mockMvc.perform(get(BASE + "/workloads/" + ids[0])).andExpect(jsonPath("$.data.status").value("RUNNING"));

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RUNNING\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RUNNING"));
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/workloads/" + ids[0])).andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FAILED\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(url)).andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/dispatches/999999/result").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RUNNING\"}"))
                .andExpect(status().isNotFound());
    }

    @DisplayName("페이지 목록은 status·workloadId 필터와 페이지 형식(5필드)을 지키고 모르는 status 는 400")
    @Test
    void page_filters() throws Exception {
        bigNode();
        long[] a = placed("dpapi-pg-a");
        long[] b = placed("dpapi-pg-b");
        mockMvc.perform(post(BASE + "/dispatches/" + b[1] + "/result").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RUNNING\"}"))
                .andExpect(status().isOk());

        JsonNode page = data(mockMvc.perform(get(BASE + "/dispatches").param("workloadId", String.valueOf(a[0]))).andExpect(status().isOk()).andReturn());
        assertEquals(new TreeSet<>(List.of("items", "page", "size", "totalElements", "totalPages")), new TreeSet<>(page.propertyNames()));
        assertEquals(1, page.get("totalElements").asLong());
        assertEquals("ACCEPTED", page.get("items").get(0).get("status").asString());

        JsonNode running = data(mockMvc.perform(get(BASE + "/dispatches").param("status", "running").param("workloadId", String.valueOf(b[0])))
                .andExpect(status().isOk()).andReturn());
        assertEquals(1, running.get("totalElements").asLong());

        mockMvc.perform(get(BASE + "/dispatches").param("status", "weird")).andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/dispatches").param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/dispatches").param("sort", "nodeId,asc")).andExpect(status().isBadRequest());
    }

    @DisplayName("상태가 모두 같아도 페이지를 이어 붙이면 id 가 첫 키 방향으로 이어지고, id 정렬은 요청 그대로 받는다")
    @Test
    void tied_status_pages_follow_id_in_first_key_direction() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Workload w = workloadRepository.save(new Workload("dpapi-tie-" + i, 1, 64));
            ids.add(dispatchRepository.save(new PlacementDispatch(w, RUN_Z, "tester")).getId());
        }
        List<Long> descending = new ArrayList<>(ids);
        descending.sort(Comparator.reverseOrder());

        List<Long> stitched = new ArrayList<>();
        for (int p = 1; p <= 3; p++) {
            JsonNode page = data(mockMvc.perform(get(BASE + "/dispatches").param("scheduleRunId", String.valueOf(RUN_Z))
                    .param("sort", "status,desc").param("size", "5").param("page", String.valueOf(p))).andExpect(status().isOk()).andReturn());
            assertEquals(12, page.get("totalElements").asLong());
            for (JsonNode row : page.get("items")) {
                assertEquals("REQUESTED", row.get("status").asString());
                stitched.add(row.get("id").asLong());
            }
        }
        assertEquals(descending, stitched, "동률은 id 내림차순 — 세 페이지(5·5·2)에 겹침·빠짐 없음");

        JsonNode byId = data(mockMvc.perform(get(BASE + "/dispatches").param("scheduleRunId", String.valueOf(RUN_Z))
                .param("sort", "id,asc").param("size", "100")).andExpect(status().isOk()).andReturn());
        List<Long> ascending = new ArrayList<>();
        for (JsonNode row : byId.get("items")) ascending.add(row.get("id").asLong());
        List<Long> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.naturalOrder());
        assertEquals(sorted, ascending, "sort=id,asc 는 요청 방향 그대로 id 오름차순 — 보조 키 중복 부착 여부는 StableSortTest 가 본다");
    }

    @DisplayName("워크로드를 삭제하면 그 디스패치 기록도 사라진다 — 실행 중이면 삭제 자체가 409")
    @Test
    void delete_workload_removes_dispatches() throws Exception {
        bigNode();
        long[] ids = placed("dpapi-del");
        mockMvc.perform(delete(BASE + "/workloads/" + ids[0])).andExpect(status().isConflict());
        mockMvc.perform(post(BASE + "/dispatches/" + ids[1] + "/result").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RUNNING\"}"));
        mockMvc.perform(post(BASE + "/dispatches/" + ids[1] + "/result").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"));
        mockMvc.perform(delete(BASE + "/workloads/" + ids[0])).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/dispatches/" + ids[1])).andExpect(status().isNotFound());
        assertTrue(dispatchRepository.findByWorkloadIdOrderByRequestedAtDesc(ids[0]).isEmpty());
    }

    @DisplayName("워크로드 응답은 23필드(요구 대역폭 추가)이고, 미인증은 403")
    @Test
    void workload_contract_unchanged_and_auth() throws Exception {
        bigNode();
        long[] ids = placed("dpapi-contract");
        JsonNode w = data(mockMvc.perform(get(BASE + "/workloads/" + ids[0])).andReturn());
        assertEquals(23, w.propertyNames().size(),
                "워크로드 응답 필드 수가 바뀌었다 — 의도한 변경이면 프런트 계약 문서의 변경 이력에도 적는다");
        mockMvc.perform(get(BASE + "/dispatches").with(anonymous())).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/dispatches/" + ids[1] + "/result").with(anonymous())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RUNNING\"}")).andExpect(status().isForbidden());
    }
}

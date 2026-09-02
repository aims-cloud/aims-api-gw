package com.innogrid.aims.scheduler.controller;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.innogrid.aims.scheduler.dto.ResponseJsonGoldenTest;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 워크로드·배치 이력·배치 정책 API 의 HTTP 계약 — 실제 컨트롤러·DB(H2)를 통과한 응답의 필드 집합.
 *
 * <p>단위 테스트({@code ResponseJsonGoldenTest}·{@code ResponseDtoEquivalenceTest})는 객체 → JSON 만 본다.
 * 여기서는 <b>요청 → 저장 → 응답</b> 전체 경로에서 DTO 로 바뀐 응답이 예전 엔티티 직렬화와 같은 필드
 * 집합을 내는지, 그리고 저장된 값(id·createdAt)이 채워져 나오는지 확인한다.</p>
 *
 * <p>노드 조회는 테스트 환경에 메트릭 저장소가 없어 빈 배열이다. 노드 응답 필드는
 * {@code NodeResourceSerializationTest} 가 고정한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class SchedulerApiContractTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void 워크로드_생성_조회_목록_응답이_같은_21필드다() throws Exception {
        Map<String, Object> created = data(mockMvc.perform(post("/api/v1/scheduler/workloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"contract-w","requiredCpu":2,"requiredMemoryMb":1024,
                                 "resourceType":"gpu","priority":"high","description":"계약 테스트"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn());

        assertEquals(ResponseJsonGoldenTest.WORKLOAD_FIELDS, new TreeSet<>(created.keySet()));
        assertNotNull(created.get("id"), "저장된 id 가 채워져야 한다");
        assertNotNull(created.get("createdAt"), "@PrePersist 로 채워진 시각이 나와야 한다");
        assertEquals("PENDING", created.get("status"));
        assertEquals("GPU", created.get("resourceType"), "열거형은 대소문자 관대 파싱 후 이름으로 나간다");
        assertEquals("HIGH", created.get("priority"));
        assertNull(created.get("retryCount"), "새 행의 재시도 횟수는 null 이다(0 이 아님)");
        assertNull(created.get("assignedNodeId"));

        Number id = (Number) created.get("id");
        Map<String, Object> fetched = data(mockMvc.perform(get("/api/v1/scheduler/workloads/" + id))
                .andExpect(status().isOk()).andReturn());
        assertSameExceptTimestamps(created, fetched, "단건 조회는 생성 응답과 같아야 한다");

        List<Map<String, Object>> listed = dataList(mockMvc.perform(get("/api/v1/scheduler/workloads"))
                .andExpect(status().isOk()).andReturn());
        Map<String, Object> inList = listed.stream()
                .filter(w -> ((Number) w.get("id")).longValue() == id.longValue())
                .findFirst().orElseThrow();
        assertEquals(ResponseJsonGoldenTest.WORKLOAD_FIELDS, new TreeSet<>(inList.keySet()));

        // 상태 필터도 같은 형태
        List<Map<String, Object>> pending = dataList(mockMvc.perform(get("/api/v1/scheduler/workloads?status=pending"))
                .andExpect(status().isOk()).andReturn());
        assertTrue(pending.stream().allMatch(w -> "PENDING".equals(w.get("status"))));

        // 워크로드별 이력은 아직 없다 — 빈 배열
        assertTrue(dataList(mockMvc.perform(get("/api/v1/scheduler/workloads/" + id + "/history"))
                .andExpect(status().isOk()).andReturn()).isEmpty());

        mockMvc.perform(delete("/api/v1/scheduler/workloads/" + id)).andExpect(status().isOk());
    }

    @Test
    void 허용되지_않는_상태_전이는_400_이고_응답_형태는_그대로다() throws Exception {
        Map<String, Object> created = data(mockMvc.perform(post("/api/v1/scheduler/workloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"contract-w2\",\"requiredCpu\":1,\"requiredMemoryMb\":512}"))
                .andExpect(status().isOk()).andReturn());
        Number id = (Number) created.get("id");

        // PENDING → RUNNING 은 허용되지 않는다(배치가 먼저)
        mockMvc.perform(patch("/api/v1/scheduler/workloads/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RUNNING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(delete("/api/v1/scheduler/workloads/" + id)).andExpect(status().isOk());
    }

    @Test
    void 배치_정책_생성_조회_수정_목록_응답이_같은_20필드다() throws Exception {
        String name = "contract-policy-" + System.nanoTime();
        Map<String, Object> created = data(mockMvc.perform(post("/api/v1/scheduler/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"계약\",\"cpuWeight\":0.3,"
                                + "\"resourceType\":\"vm\",\"active\":false}"))
                .andExpect(status().isOk()).andReturn());

        assertEquals(ResponseJsonGoldenTest.POLICY_FIELDS, new TreeSet<>(created.keySet()));
        assertEquals(0.3, created.get("cpuWeight"));
        assertEquals(0.25, created.get("memoryWeight"), "지정하지 않은 가중치는 기본값");
        assertEquals("VM", created.get("resourceType"));
        assertEquals(false, created.get("active"));
        assertEquals(true, created.get("powerAwareEnabled"));
        assertNotNull(created.get("createdAt"));

        Number id = (Number) created.get("id");
        Map<String, Object> fetched = data(mockMvc.perform(get("/api/v1/scheduler/policies/" + id))
                .andExpect(status().isOk()).andReturn());
        assertSameExceptTimestamps(created, fetched, "단건 조회는 생성 응답과 같아야 한다");

        Map<String, Object> updated = data(mockMvc.perform(put("/api/v1/scheduler/policies/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"수정됨\",\"powerAwareEnabled\":false}"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(ResponseJsonGoldenTest.POLICY_FIELDS, new TreeSet<>(updated.keySet()));
        assertEquals("수정됨", updated.get("description"));
        assertEquals(false, updated.get("powerAwareEnabled"));
        assertEquals(0.3, updated.get("cpuWeight"), "부분 수정 — 보내지 않은 필드는 그대로");

        List<Map<String, Object>> listed = dataList(mockMvc.perform(get("/api/v1/scheduler/policies"))
                .andExpect(status().isOk()).andReturn());
        assertTrue(listed.stream().anyMatch(p -> name.equals(p.get("name"))));
        listed.forEach(p -> assertEquals(ResponseJsonGoldenTest.POLICY_FIELDS, new TreeSet<>(p.keySet())));

        mockMvc.perform(delete("/api/v1/scheduler/policies/" + id)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/scheduler/policies/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void 배치_이력_목록은_배열이다() throws Exception {
        // 테스트 환경에는 노드가 없어 배치가 일어나지 않으므로 행이 없을 수 있다. 형태만 본다.
        mockMvc.perform(get("/api/v1/scheduler/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void 노드_목록은_배열이고_없는_노드는_404_다() throws Exception {
        mockMvc.perform(get("/api/v1/scheduler/nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(get("/api/v1/scheduler/nodes/no-such-node"))
                .andExpect(status().isNotFound());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /**
     * 생성 응답은 메모리의 나노초 시각, 재조회는 DB 왕복 후 마이크로초로 잘린 시각을 낸다.
     * 이 차이는 DTO 이전에도 있던 저장소 특성이라 계약 비교에서 시각만 뺀다(둘 다 채워져 있어야 한다).
     */
    private static void assertSameExceptTimestamps(Map<String, Object> a, Map<String, Object> b, String message) {
        assertNotNull(a.get("createdAt"));
        assertNotNull(b.get("createdAt"));
        Map<String, Object> a2 = new java.util.HashMap<>(a);
        Map<String, Object> b2 = new java.util.HashMap<>(b);
        a2.remove("createdAt");
        b2.remove("createdAt");
        assertEquals(a2, b2, message);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (Map<String, Object>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dataList(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (List<Map<String, Object>>) body.get("data");
    }
}

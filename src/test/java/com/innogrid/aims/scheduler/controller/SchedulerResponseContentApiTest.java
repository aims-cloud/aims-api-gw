package com.innogrid.aims.scheduler.controller;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.innogrid.aims.common.dto.ApiResponse;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.ConstraintDescriptionResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.ConsolidationService;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;
import com.innogrid.aims.scheduler.service.PowerEfficiencyService;
import com.innogrid.aims.scheduler.service.PowerOptimizationService;
import com.innogrid.aims.scheduler.service.SchedulingService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스케줄러 응답 열 곳의 내용 고정 — 리소스 종류·엔진·일괄 재개·냉각 대기는 키와 값을 순서와 관계없이,
 * 제약 설명·단건 모의 배치·효율 추세는 서비스 결과 직렬화와 바이트까지, 진단 셋은 평가 시각을 뺀 값으로 비교한다.
 * 응답 타입을 바꾸기 전과 후에 같은 시험이 통과해야 하므로 응답 타입이나 서비스 반환 타입을 참조하지 않는다.
 * 냉각 대기의 남은 분은 표시 시각을 과거로 옮겨 「설정값 − 지난 분」인지와 설정값만큼 지나면 목록에서 빠지는지를 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class SchedulerResponseContentApiTest {

    private static final String BASE = "/api/v1/scheduler";
    private static final String PREFIX = "rsc-";
    private static final String NODE = PREFIX + "node:9100";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingPolicyRepository policyRepository;
    @Autowired SchedulingService schedulingService;
    @Autowired EngineRegistry engineRegistry;
    @Autowired ThermalModel thermalModel;
    @Autowired SchedulerProperties properties;
    @Autowired PowerEfficiencyService powerEfficiencyService;
    @Autowired PowerOptimizationService powerOptimizationService;
    @Autowired ConsolidationService consolidationService;
    @MockitoBean NodeResourceCollector collector;

    @BeforeEach
    void stubNodes() {
        // 수집기는 부를 때마다 새 노드 객체를 준다 — 예약 반영과 온도 상태 갱신이 객체를 고치므로 같은 객체를 다시 주면 안 된다
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource(PREFIX + "mdc", NODE, 8, 65_536, 0, 10, 20, 0, 100, 500, 36.5, 0, 0)), List.of()));
    }

    @AfterEach
    void clean() {
        thermalModel.clearCritical(NODE);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
    }

    @DisplayName("리소스 종류 목록은 네 종류의 code·label 을 열거 순서로 돌려준다")
    @Test
    void resource_types_keep_codes_and_labels() throws Exception {
        JsonNode data = data(mockMvc.perform(get(BASE + "/workloads/resource-types")).andExpect(status().isOk()).andReturn());
        assertEquals(objectMapper.readTree("[{\"code\":\"VM\",\"label\":\"VM\"},{\"code\":\"GPU\",\"label\":\"GPU\"},"
                + "{\"code\":\"NPU\",\"label\":\"NPU\"},{\"code\":\"CONTAINER\",\"label\":\"CONTAINER\"}]"), data);
    }

    @DisplayName("엔진 목록은 레지스트리가 설명하는 id·name 그대로다")
    @Test
    void engines_keep_registry_entries() throws Exception {
        JsonNode data = data(mockMvc.perform(get(BASE + "/engines")).andExpect(status().isOk()).andReturn());
        assertEquals(objectMapper.readTree(objectMapper.writeValueAsString(engineRegistry.describe())), data);
        assertEquals(3, data.size());
        Set<String> ids = new TreeSet<>();
        data.forEach(e -> ids.add(e.get("id").asString()));
        assertEquals(Set.of("power", "temperature", "weighted"), ids);
    }

    @DisplayName("일괄 재개는 조건 없음 400, 0건이면 빈 목록, 1건이면 건수와 워크로드 단건 조회와 같은 본문이다")
    @Test
    void resume_exhausted_keeps_count_and_workloads() throws Exception {
        mockMvc.perform(post(BASE + "/workloads/resume-exhausted"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("리소스 종류나 기간 중 하나는 지정해야 합니다 — 조건 없는 전체 재개는 받지 않습니다"));

        JsonNode none = data(mockMvc.perform(post(BASE + "/workloads/resume-exhausted")
                .param("resourceType", "NPU").param("from", "2000-01-01T00:00:00Z").param("to", "2000-01-01T00:00:01Z"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(objectMapper.readTree("{\"resumedCount\":0,\"workloads\":[]}"), none);

        Workload w = new Workload(PREFIX + "npu", 1, 64);
        w.setResourceType(Workload.ResourceType.NPU);
        for (int i = 0; i < properties.getMaxRetryCount(); i++) w.markFailed("rsc");
        ReflectionTestUtils.setField(w, "lastFailedAt", OffsetDateTime.parse("2001-02-03T04:05:06Z"));
        long id = workloadRepository.saveAndFlush(w).getId();

        JsonNode one = data(mockMvc.perform(post(BASE + "/workloads/resume-exhausted")
                .param("resourceType", "NPU").param("from", "2001-02-03T04:05:00Z").param("to", "2001-02-03T04:05:59Z"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(Set.of("resumedCount", "workloads"), new TreeSet<>(names(one)));
        assertEquals(1, one.get("resumedCount").asInt());
        assertEquals(1, one.get("workloads").size());
        JsonNode single = data(mockMvc.perform(get(BASE + "/workloads/" + id)).andExpect(status().isOk()).andReturn());
        assertEquals(single, one.get("workloads").get(0), "되살린 워크로드 본문 = 단건 조회");
        assertEquals("PENDING", single.get("status").asString());
        assertEquals(0, single.get("retryCount").asInt());
        assertTrue(single.get("lastFailedAt").isNull());
    }

    @DisplayName("냉각 대기 목록은 표시 전 비어 있고 표시 뒤 노드의 다섯 값을 돌려준다")
    @Test
    void thermal_cooldowns_keep_five_values() throws Exception {
        assertEquals(objectMapper.readTree("[]"), data(mockMvc.perform(get(BASE + "/thermal/cooldowns")).andExpect(status().isOk()).andReturn()));

        thermalModel.markCritical(NODE);
        String state = data(mockMvc.perform(get(BASE + "/nodes/" + NODE)).andExpect(status().isOk()).andReturn()).get("thermalState").asString();
        JsonNode data = data(mockMvc.perform(get(BASE + "/thermal/cooldowns")).andExpect(status().isOk()).andReturn());
        assertEquals(objectMapper.readTree("[{\"mdcId\":\"" + PREFIX + "mdc\",\"nodeId\":\"" + NODE + "\",\"temperatureCelsius\":36.5,"
                + "\"thermalState\":\"" + state + "\",\"remainingCooldownMinutes\":" + properties.getThermal().getCooldownMinutes() + "}]"), data);
    }

    @DisplayName("냉각 대기의 남은 분은 설정값에서 표시 뒤 지난 분을 뺀 값이고 설정값만큼 지나면 목록에서 빠진다")
    @Test
    void thermal_cooldown_remaining_subtracts_elapsed_minutes() throws Exception {
        long cooldown = properties.getThermal().getCooldownMinutes();
        assertTrue(cooldown > 3, "시험 전제: 냉각 설정값이 3분보다 길다 — " + cooldown);
        // 모델의 시계는 시스템 시계라 돌릴 수 없다 — 표시 시각을 과거로 옮겨 지난 시간을 만든다(분 내림까지 55초 여유)
        thermalModel.markCritical(NODE);
        @SuppressWarnings("unchecked")
        Map<String, Instant> since = (Map<String, Instant>) ReflectionTestUtils.getField(thermalModel, "criticalSince");
        since.put(NODE, Instant.now().minus(Duration.ofMinutes(3)).minusSeconds(5));
        JsonNode data = data(mockMvc.perform(get(BASE + "/thermal/cooldowns")).andExpect(status().isOk()).andReturn());
        assertEquals(1, data.size(), data.toString());
        assertEquals(cooldown - 3, data.get(0).get("remainingCooldownMinutes").asLong());

        since.put(NODE, Instant.now().minus(Duration.ofMinutes(cooldown)).minusSeconds(5));
        assertEquals(objectMapper.readTree("[]"), data(mockMvc.perform(get(BASE + "/thermal/cooldowns")).andExpect(status().isOk()).andReturn()));
        assertTrue(since.keySet().stream().noneMatch(k -> k.startsWith(PREFIX)), "설정값만큼 지난 항목은 목록 조회가 지운다");
    }

    @DisplayName("제약 설명·단건 모의 배치·효율 추세 본문은 서비스 결과를 직렬화한 바이트와 같다")
    @Test
    void typed_bodies_equal_runtime_serialization() throws Exception {
        assertArrayEquals(objectMapper.writeValueAsBytes(ApiResponse.ok(ConstraintDescriptionResponse.all())),
                body(mockMvc.perform(get(BASE + "/constraints")).andExpect(status().isOk()).andReturn()), "제약 설명");

        schedulingService.resolvePolicy(null);   // default 정책이 없으면 모의 배치가 만든다 — 두 호출이 같은 정책을 보도록 먼저 만든다
        Workload w = new Workload(PREFIX + "dry", 1, 64);
        long id = workloadRepository.saveAndFlush(w).getId();
        byte[] viaHttp = body(mockMvc.perform(post(BASE + "/workloads/" + id + "/schedule").param("dryRun", "true").param("engine", "weighted"))
                .andExpect(status().isOk()).andReturn());
        assertArrayEquals(objectMapper.writeValueAsBytes(ApiResponse.ok(schedulingService.dryRun(id, "weighted"))), viaHttp, "단건 모의 배치");
        assertEquals(Workload.WorkloadStatus.PENDING, workloadRepository.findById(id).orElseThrow().getStatus());

        OffsetDateTime from = OffsetDateTime.parse("2026-09-01T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-09-01T01:00:00Z");
        byte[] trend = body(mockMvc.perform(get(BASE + "/nodes/" + NODE + "/power-efficiency/trend")
                .param("from", "2026-09-01T00:00:00Z").param("to", "2026-09-01T01:00:00Z").param("step", "5m"))
                .andExpect(status().isOk()).andReturn());
        assertArrayEquals(objectMapper.writeValueAsBytes(ApiResponse.ok(powerEfficiencyService.trend(NODE, from, to, "5m"))), trend, "효율 추세");
    }

    @DisplayName("전력 효율·전력 최적화·통합 진단은 평가 시각을 뺀 값이 서비스 결과와 같다")
    @Test
    void diagnostics_equal_service_output_except_time() throws Exception {
        schedulingService.resolvePolicy(null);   // default 정책 행이 있는 상태에서 HTTP 응답과 직접 호출을 비교한다(행이 없는 경우는 아래 정책 행 테스트가 본다)

        JsonNode efficiency = data(mockMvc.perform(get(BASE + "/nodes/power-efficiency")).andExpect(status().isOk()).andReturn());
        assertEquals(withoutTime(objectMapper.readTree(objectMapper.writeValueAsString(powerEfficiencyService.evaluateCurrent()))),
                withoutTime(efficiency), "전력 효율");
        assertEquals(1, efficiency.get("nodes").size());

        JsonNode optimization = data(mockMvc.perform(get(BASE + "/optimization/power")).andExpect(status().isOk()).andReturn());
        assertEquals(withoutTime(objectMapper.readTree(objectMapper.writeValueAsString(powerOptimizationService.diagnoseCurrent()))),
                withoutTime(optimization), "전력 최적화");
        assertEquals(1, optimization.get("mdcs").size());

        JsonNode consolidation = data(mockMvc.perform(get(BASE + "/optimization/consolidation")).andExpect(status().isOk()).andReturn());
        assertEquals(withoutTime(objectMapper.readTree(objectMapper.writeValueAsString(consolidationService.diagnose()))),
                withoutTime(consolidation), "통합 진단");
    }

    @DisplayName("통합 진단 조회는 정책이 없어도 정책 행을 만들지 않는다")
    @Test
    void consolidation_get_creates_no_policy_row() throws Exception {
        List<SchedulingPolicy> snapshot = policyRepository.findAll();
        policyRepository.deleteAll();
        try {
            mockMvc.perform(get(BASE + "/optimization/consolidation")).andExpect(status().isOk());

            assertEquals(0, policyRepository.count(), "읽기 진단은 정책 행을 만들지 않는다");
            assertTrue(policyRepository.findByName(SchedulingService.DEFAULT_POLICY_NAME).isEmpty(), "default 행도 만들지 않는다");
        } finally {
            // 지운 정책을 이름·설명·종류·활성 상태로 되돌린다(id 는 새로 매김 · 나머지 값은 기본값 — PolicyRulesScenarioTest.restore() 와 같은 방식)
            policyRepository.deleteAll();
            for (SchedulingPolicy p : snapshot) {
                SchedulingPolicy copy = new SchedulingPolicy(p.getName(), p.getDescription());
                copy.setResourceType(p.getResourceType());
                copy.setActive(p.isActive());
                policyRepository.save(copy);
            }
        }
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private static byte[] body(MvcResult result) {
        return result.getResponse().getContentAsByteArray();
    }

    private static JsonNode withoutTime(JsonNode node) {
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        copy.remove("evaluatedAt");
        return copy;
    }

    private static List<String> names(JsonNode node) {
        List<String> keys = new java.util.ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }
}

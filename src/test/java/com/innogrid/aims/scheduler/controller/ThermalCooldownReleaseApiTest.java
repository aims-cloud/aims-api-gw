package com.innogrid.aims.scheduler.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 냉각 대기 해제 API — 실제 평가 경로(단건 모의 배치)로 건 대기를 풀고, 대기가 없거나 만료만 남은 노드는 404이며,
 * 해제 뒤에도 뜨거운 노드는 다음 평가가 온도 초과로 다시 빼고 대기를 다시 건다.
 * 노드 id에 점과 콜론을 넣어 경로 변수로 그대로 닿는지도 함께 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ThermalCooldownReleaseApiTest {

    private static final String BASE = "/api/v1/scheduler";
    private static final String PREFIX = "tcr-";
    private static final String NODE = PREFIX + "10.0.0.5:9100";
    /** 어느 정책의 온도 상한보다도 높은 실측 온도 — 단건 모의 배치가 이 노드에 대기를 건다. */
    private static final double HOT = 150.0;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired ThermalModel thermalModel;
    @Autowired SchedulerProperties properties;
    @MockitoBean NodeResourceCollector collector;

    @BeforeEach
    void stubNodes() {
        // 수집기는 부를 때마다 새 노드 객체를 준다 — 예약 반영과 온도 상태 갱신이 객체를 고치므로 같은 객체를 다시 주면 안 된다
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource(PREFIX + "mdc", NODE, 8, 65_536, 0, 10, 20, 0, 100, 500, HOT, 0, 0)), List.of()));
    }

    @AfterEach
    void clean() {
        thermalModel.clearCritical(NODE);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
    }

    @DisplayName("단건 모의 배치가 건 냉각 대기를 풀면 지운 상한(오름차순)과 가장 긴 남은 분을 선언 순서의 키로 돌려주고 목록에서 빠진다")
    @Test
    void release_removes_cooldown_set_by_dry_run() throws Exception {
        long id = workloadRepository.saveAndFlush(new Workload(PREFIX + "w", 1, 64)).getId();
        assertEquals("TEMPERATURE_EXCEEDED", dryRunCode(id), "실측 온도가 상한을 넘어 대기가 걸린다");
        assertEquals("COOLING_DOWN", dryRunCode(id), "다음 평가는 대기에 막힌다");
        double limit = thermalModel.cooldownMarks().get(NODE).keySet().iterator().next();
        long cooldown = properties.getThermal().getCooldownMinutes();
        // 상한이 더 낮은 대기를 3분 먼저 건 것처럼 하나 더 둔다 — 응답의 상한은 오름차순이고 남은 분은 둘 중 긴 쪽(설정값)이다
        double lower = limit - 10;
        @SuppressWarnings("unchecked")
        Map<String, Map<Double, Instant>> since = (Map<String, Map<Double, Instant>>) ReflectionTestUtils.getField(thermalModel, "criticalSince");
        Map<Double, Instant> two = new HashMap<>(since.get(NODE));
        two.put(lower, Instant.now().minus(Duration.ofMinutes(3)).minusSeconds(5));
        since.put(NODE, Map.copyOf(two));

        JsonNode listed = data(mockMvc.perform(get(BASE + "/thermal/cooldowns")).andExpect(status().isOk()).andReturn());
        assertEquals(1, listed.size(), listed.toString());
        assertEquals(NODE, listed.get(0).get("nodeId").asString());

        JsonNode released = data(mockMvc.perform(delete(BASE + "/thermal/cooldowns/" + NODE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn());
        List<String> keys = new ArrayList<>();
        released.propertyNames().forEach(keys::add);
        assertEquals(List.of("nodeId", "releasedLimitsCelsius", "remainingCooldownMinutes"), keys);
        assertEquals(objectMapper.readTree("{\"nodeId\":\"" + NODE + "\",\"releasedLimitsCelsius\":[" + lower + "," + limit + "],"
                + "\"remainingCooldownMinutes\":" + cooldown + "}"), released);

        assertEquals(objectMapper.readTree("[]"), data(mockMvc.perform(get(BASE + "/thermal/cooldowns")).andExpect(status().isOk()).andReturn()));
        assertFalse(thermalModel.isCoolingDown(NODE));
    }

    @DisplayName("냉각 대기가 없는 노드를 풀면 404 와 사유 문구다")
    @Test
    void release_without_cooldown_is_404() throws Exception {
        mockMvc.perform(delete(BASE + "/thermal/cooldowns/" + NODE))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("냉각 대기 중인 노드가 아닙니다: " + NODE));
    }

    @DisplayName("만료된 대기만 남은 노드를 풀면 404 이고 그 기록은 지워진다")
    @Test
    void release_with_only_expired_cooldown_is_404() throws Exception {
        long cooldown = properties.getThermal().getCooldownMinutes();
        // 모델의 시계는 시스템 시계라 돌릴 수 없다 — 표시 시각을 과거로 옮겨 만료를 만든다
        thermalModel.markCritical(NODE, 75.0);
        @SuppressWarnings("unchecked")
        Map<String, Map<Double, Instant>> since = (Map<String, Map<Double, Instant>>) ReflectionTestUtils.getField(thermalModel, "criticalSince");
        since.put(NODE, Map.of(75.0, Instant.now().minus(Duration.ofMinutes(cooldown)).minusSeconds(5)));

        mockMvc.perform(delete(BASE + "/thermal/cooldowns/" + NODE))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("냉각 대기 중인 노드가 아닙니다: " + NODE));
        assertFalse(thermalModel.cooldownMarks().containsKey(NODE), "만료된 기록도 지운다");
    }

    @DisplayName("해제 뒤에도 상한을 넘는 노드는 다음 평가가 온도 초과로 빼고 대기를 다시 건다")
    @Test
    void hot_node_is_excluded_again_after_release() throws Exception {
        long id = workloadRepository.saveAndFlush(new Workload(PREFIX + "again", 1, 64)).getId();
        assertEquals("TEMPERATURE_EXCEEDED", dryRunCode(id));
        mockMvc.perform(delete(BASE + "/thermal/cooldowns/" + NODE)).andExpect(status().isOk());

        assertEquals("TEMPERATURE_EXCEEDED", dryRunCode(id), "대기가 아니라 현재 온도로 다시 판정한다");
        JsonNode listed = data(mockMvc.perform(get(BASE + "/thermal/cooldowns")).andExpect(status().isOk()).andReturn());
        assertEquals(1, listed.size(), "대기가 다시 걸린다 — " + listed);
        assertTrue(thermalModel.isCoolingDown(NODE));
    }

    /** 단건 모의 배치의 첫 후보 제외 코드. */
    private String dryRunCode(long workloadId) throws Exception {
        JsonNode data = data(mockMvc.perform(post(BASE + "/workloads/" + workloadId + "/schedule").param("dryRun", "true"))
                .andExpect(status().isOk()).andReturn());
        assertFalse(data.get("success").asBoolean(), data.toString());
        return data.get("candidateScores").get(0).get("exclusionCode").asString();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }
}

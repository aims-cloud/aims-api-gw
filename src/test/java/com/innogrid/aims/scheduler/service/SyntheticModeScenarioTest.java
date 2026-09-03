package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.innogrid.aims.scheduler.dto.PowerEfficiencyResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationTrendResponse;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.metrics.SwitchingMetricsGateway;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 모의 메트릭 모드 시나리오 — 플래그를 켠 별도 컨텍스트에서 스케줄러 전 경로가 가상 클러스터(MDC 3곳·노드 8대)로 동작하는가.
 *
 * <pre>
 * 수집     8대 전부 후보(IPMI 없는 3대는 전력·온도 0·미계측, 제외 0) · GPU 노드 2대의 GPU 수 4·2 · MDC 3곳
 * 효율     IPMI 5대 계측 · busan-02(유휴 고전력)가 최저 등급 쪽
 * 진단     통합 진단 200 형태 · 후보 8
 * 추세     seoul-01 24시간/5분 → 289 지점, 전력·CPU 채워짐
 * 배치     GPU 워크로드 dryRun → GPU 노드(seoul-02 또는 busan-03) 선택 · 디스크 190GB 요구 → daejeon-02(96% 사용)는 제외
 * 표시     /metrics-source = synthetic, 6필드
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
@TestPropertySource(properties = "aims.scheduler.mock-enabled=true")
class SyntheticModeScenarioTest {

    @Autowired NodeResourceCollector collector;
    @Autowired SchedulingService schedulingService;
    @Autowired PowerEfficiencyService efficiency;
    @Autowired PowerOptimizationService optimization;
    @Autowired SwitchingMetricsGateway gateway;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @AfterEach
    void clean() {
        historyRepository.findAll().stream().filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith("syn-")).forEach(historyRepository::delete);
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith("syn-")).forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith("syn-")).forEach(workloadRepository::delete);
    }

    @DisplayName("수집 — 8대 전부 후보, 제외 0, MDC 3곳, IPMI 없는 3대는 미계측, GPU 노드 2대")
    @Test
    void collect_eight_nodes() {
        NodeResourceCollector.Snapshot s = collector.collect();
        assertEquals(8, s.nodes().size());
        assertTrue(s.excluded().isEmpty());
        assertEquals(3, s.nodes().stream().map(NodeResource::getMdcId).distinct().count());
        List<NodeResource> unmeasured = s.nodes().stream().filter(n -> n.getPowerSource() == NodeResource.PowerSource.NONE).toList();
        assertEquals(3, unmeasured.size());
        assertTrue(unmeasured.stream().allMatch(n -> n.getPowerWatts() == 0 && n.getTemperatureCelsius() == 0));
        assertEquals(Set.of(4, 2), s.nodes().stream().filter(n -> n.getTotalGpu() > 0).map(NodeResource::getTotalGpu).collect(Collectors.toSet()));
        NodeResource hot = s.nodes().stream().filter(n -> n.getNodeId().startsWith("seoul-03")).findFirst().orElseThrow();
        assertTrue(hot.getTemperatureCelsius() >= 62, "뜨거운 노드: " + hot.getTemperatureCelsius());
        assertTrue(s.nodes().stream().allMatch(n -> n.getTotalCpu() > 0 && n.getTotalMemoryMb() > 0));
    }

    @DisplayName("전력 효율 평가 — 노드 8 중 계측 5대가 평가 가능, 등급이 붙고 유휴 고전력 노드(busan-02)가 가장 낮은 PEI. 통합 진단은 후보 8대")
    @Test
    void efficiency_and_diagnosis() {
        PowerEfficiencyResponse eff = efficiency.evaluateCurrent();
        assertEquals(8, eff.summary().totalNodes());
        assertEquals(5, eff.summary().evaluableNodes(), "IPMI 있는 노드만 평가 가능");
        assertTrue(eff.summary().gradeAvailable(), eff.summary().gradeUnavailableReason());
        PowerEfficiencyResponse.NodeEfficiency worst = eff.nodes().stream().filter(n -> n.peiRaw() != null)
                .min((a, b) -> Double.compare(a.peiRaw(), b.peiRaw())).orElseThrow();
        assertTrue(worst.nodeId().startsWith("busan-02"), "유휴인데 고전력인 노드가 최저 PEI: " + worst.nodeId());
        assertEquals(3, eff.nodes().stream().filter(n -> n.peiRaw() == null).count(), "미계측 3대는 PEI null");

        PowerOptimizationResponse diag = optimization.diagnoseCurrent();
        assertEquals(8, diag.cluster().totalNodes());
        assertNotNull(diag.findings());
    }

    @DisplayName("추세 — 24시간/5분 은 289 지점, 지점마다 노드 8·계측 5·전력 합 > 0 이고 measured 다")
    @Test
    void trend_points() {
        PowerOptimizationTrendResponse t = optimization.trend(null, null, "5m");
        assertEquals(289, t.points().size(), "24h/5m + 1");
        PowerOptimizationTrendResponse.Point p = t.points().get(0);
        assertEquals(8, p.totalNodes());
        assertEquals(5, p.evaluableNodes(), "IPMI 없는 3대는 전력이 없어 평가 불가");
        assertTrue(p.measured());
        assertTrue(p.totalPowerWatts() > 0);
        assertNotEquals(t.points().get(0).totalPowerWatts(), t.points().get(3).totalPowerWatts(), "시간에 따라 값이 움직인다");
    }

    @DisplayName("배치 — GPU 워크로드는 GPU 노드로, 디스크 190GB 요구는 디스크 부족 노드를 피한다")
    @Test
    void placement_uses_synthetic_cluster() {
        Workload gpu = new Workload("syn-gpu", 2, 4096);
        gpu.setRequiredGpu(1);
        gpu.setResourceType(Workload.ResourceType.GPU);
        gpu = workloadRepository.saveAndFlush(gpu);
        SchedulingResult r = schedulingService.scheduleWorkload(gpu.getId(), "weighted");
        assertTrue(r.success(), r.reason());
        assertTrue(r.selectedNodeId().startsWith("seoul-02") || r.selectedNodeId().startsWith("busan-03"), "GPU 노드 선택: " + r.selectedNodeId());

        Workload disk = new Workload("syn-disk", 1, 1024);
        disk.setRequiredDiskGb(190);
        disk = workloadRepository.saveAndFlush(disk);
        SchedulingResult d = schedulingService.scheduleWorkload(disk.getId(), "weighted");
        assertTrue(d.success(), d.reason());
        assertFalse(d.selectedNodeId().startsWith("daejeon-02"), "디스크 96% 노드는 피한다: " + d.selectedNodeId());
    }

    @DisplayName("표시 API — /metrics-source 는 synthetic·mockEnabled true·파일·이름·MDC 3·노드 8 의 6필드")
    @Test
    void metrics_source_synthetic() throws Exception {
        assertEquals("synthetic", gateway.source());
        JsonNode d = objectMapper.readTree(mockMvc.perform(get("/api/v1/scheduler/metrics-source")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("synthetic")).andReturn().getResponse().getContentAsString()).get("data");
        assertEquals(new TreeSet<>(List.of("source", "mockEnabled", "clusterFile", "clusterName", "mdcCount", "nodeCount")), new TreeSet<>(d.propertyNames()));
        assertTrue(d.get("mockEnabled").asBoolean());
        assertEquals(3, d.get("mdcCount").asInt());
        assertEquals(8, d.get("nodeCount").asInt());
        assertEquals("demo-3mdc-8nodes", d.get("clusterName").asString());
    }
}

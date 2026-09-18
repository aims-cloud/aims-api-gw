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
        assertTrue(hot.getTemperatureCelsius() >= 65 && hot.getTemperatureCelsius() <= 80, "뜨거운 노드는 경고 구간: " + hot.getTemperatureCelsius());
        NodeResource idle = s.nodes().stream().filter(n -> n.getNodeId().startsWith("busan-02")).findFirst().orElseThrow();
        assertTrue(idle.getCpuUsagePercent() < 5, "유휴 노드는 가동 판정(5%) 아래: " + idle.getCpuUsagePercent());
        assertTrue(s.nodes().stream().allMatch(n -> n.getTotalCpu() > 0 && n.getTotalMemoryMb() > 0));
    }

    @DisplayName("전력 효율 평가 — 노드 8 중 계측 5대가 평가 가능, 등급이 붙고 유휴 고전력 노드(busan-02)가 가장 낮은 PEI. 통합 진단은 후보 8대")
    @Test
    void efficiency_and_diagnosis() {
        PowerEfficiencyResponse eff = efficiency.evaluateCurrent();
        assertEquals(8, eff.summary().totalNodes());
        assertEquals(5, eff.summary().evaluableNodes(), "IPMI 있는 노드만 평가 가능");
        assertTrue(eff.summary().gradeAvailable(), eff.summary().gradeUnavailableReason());
        // 시각과 무관한 성질만 단언한다. "최저 PEI 는 언제나 busan-02" 는 여유 노드(busan-01)의 CPU 가 0 에 가까워지는
        // 구간(한 주기의 8.6%)에 뒤집힌다 — 일을 안 하는 노드의 PEI 도 0 에 가깝기 때문이다. 한 주기 전체를 훑어
        // 확인한 불변 성질은 metrics/SyntheticModeInvariantTest 에 있다.
        PowerEfficiencyResponse.NodeEfficiency idleHighPower = eff.nodes().stream()
                .filter(n -> n.nodeId().startsWith("busan-02")).findFirst().orElseThrow();
        assertNotNull(idleHighPower.peiRaw(), "유휴 고전력 노드는 IPMI 계측 노드라 PEI 가 있다");
        List<PowerEfficiencyResponse.NodeEfficiency> activeMeasured = eff.nodes().stream()
                .filter(n -> n.peiRaw() != null && n.cpuUsagePercent() > 5).toList();
        assertFalse(activeMeasured.isEmpty(), "가동 중인 계측 노드가 있어야 비교가 성립한다");
        assertTrue(activeMeasured.stream().allMatch(n -> idleHighPower.peiRaw() < n.peiRaw()),
                "유휴인데 고전력인 노드는 가동 중인 계측 노드보다 효율이 낮다: " + idleHighPower.peiRaw() + " vs "
                        + activeMeasured.stream().map(n -> n.nodeId() + "=" + n.peiRaw()).toList());
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

    @DisplayName("구간 에너지 — 실값으로 소비·회수 가능이 나오고 추정분이 갈려 나온다")
    @Test
    void interval_energy_on_real_values() {
        // 배포 서버는 노드 1대·전력 0 이라 이 경로를 실값으로 볼 수 없다. 가상 클러스터가 그 자리를 대신한다 —
        // IPMI 있음 5대·없음 3대라 "일부만 계측" 이라는 실환경 모양을 그대로 만든다.
        PowerOptimizationTrendResponse.Energy energy = optimization.trend(null, null, "5m").energy();

        assertNotNull(energy, "추세를 낼 수 있으면 에너지 요약도 낸다");
        assertNotNull(energy.consumedKwh(), "계측 노드가 5대 있으므로 소비 에너지가 나온다");
        assertTrue(energy.consumedKwh() > 0);

        assertNotNull(energy.integratedFrom());
        assertNotNull(energy.integratedTo());
        assertTrue(energy.integratedHours() > 0 && energy.integratedHours() <= 24.01,
                "잰 시간은 물은 구간을 넘지 않는다: " + energy.integratedHours());
        assertTrue(energy.integratedFrom().isBefore(energy.integratedTo()));
    }

    @DisplayName("과거 구간을 물으면 그 구간 안에서만 적분한다")
    @Test
    void a_past_window_is_integrated_inside_that_window() {
        java.time.OffsetDateTime to = java.time.OffsetDateTime.now().minusHours(24);
        java.time.OffsetDateTime from = to.minusHours(2);

        PowerOptimizationTrendResponse.Energy past = optimization.trend(from, to, "5m").energy();

        assertNotNull(past.consumedKwh());
        assertNotNull(past.integratedFrom());
        assertNotNull(past.integratedTo());
        // 즉시 질의로 구간 평균을 받으면 언제나 현재 시각에서 평가되어, 어제를 물어도 오늘 값이 온다.
        // 이미 받아 둔 구간 시계열을 적분하면 그 일이 생기지 않는다 — 잰 구간이 물은 구간 안에 있어야 한다.
        assertFalse(past.integratedFrom().isBefore(from.minusMinutes(6)), "잰 구간이 물은 구간보다 앞선다");
        assertFalse(past.integratedTo().isAfter(to.plusMinutes(6)), "잰 구간이 물은 구간을 넘어간다");
        assertTrue(past.integratedHours() <= 2.01, "2시간을 물었는데 더 길게 쟀다: " + past.integratedHours());
    }

    @DisplayName("구멍이 없는 판에서는 구간 에너지가 지점 적분과 맞아떨어진다")
    @Test
    void interval_energy_agrees_with_the_point_integral_when_nothing_is_missing() {
        PowerOptimizationTrendResponse t = optimization.trend(null, null, "5m");

        double stepHours = 5.0 / 60.0;
        double fromPoints = 0;
        for (int i = 1; i < t.points().size(); i++) {
            Double a = t.points().get(i - 1).totalPowerWatts();
            Double b = t.points().get(i).totalPowerWatts();
            if (a != null && b != null) fromPoints += (a + b) / 2.0 * stepHours / 1000.0;
        }

        assertTrue(fromPoints > 0, "지점 적분이 0 이면 이 비교에 뜻이 없다");
        assertNotNull(t.energy().consumedKwh());
        // 이 클러스터는 IPMI 없는 3대의 시리즈가 통째로 없다 — 시리즈 안에 구멍이 뚫린 것이 아니다.
        // 그런 판에서는 두 계산이 수학적으로 같아야 하고, 갈리면 눈금이 어긋난 것이다.
        // 두 계산이 실제로 달라지는 것은 시리즈 중간이 비는 경우이고, 그 자리는 IntervalEnergyTest 가 값으로 고정한다.
        assertEquals(Math.round(fromPoints * 10), Math.round(t.energy().consumedKwh() * 10),
                "구간 에너지와 지점 적분의 눈금이 어긋난다");
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

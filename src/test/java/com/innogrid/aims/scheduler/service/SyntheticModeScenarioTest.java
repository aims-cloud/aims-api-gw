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
 * 추세     seoul-01 24시간/5분 → 289 지점, 전력·CPU 채워짐 · MDC 3곳 추세를 더하면 클러스터 추세(노드 8)
 * 배치     GPU 워크로드 dryRun → GPU 노드(seoul-02 또는 busan-03) 선택 · 디스크 190GB 요구 → daejeon-02(96% 사용)는 제외
 * 표시     /metrics-source = synthetic, 6필드
 * 후보     /node-exporter-mappings/candidates — DCGM 후보 2(seoul-02·busan-03, 호스트명 일치) · IPMI 후보 5 · 노드 8 · unavailable 없음
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
        // 시각과 무관한 성질만 확인한다. "최저 PEI는 언제나 busan-02"는 여유 노드(busan-01)의 CPU가 0에 가까워지는
        // 구간(한 주기의 8.6%)에 뒤집힌다 — 일을 안 하는 노드의 PEI도 0에 가깝기 때문이다. 한 주기 전체를 훑어
        // 확인한 불변 성질은 metrics/SyntheticModeInvariantTest에 있다.
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

    @DisplayName("전력 효율 코어 기준 — 8대 모두 코어 수를 싣고, 계측 5대만 사용 코어 환산 효율과 1~5 의 코어 기준 순위를 받는다")
    @Test
    void efficiency_core_basis() {
        java.util.Map<String, Integer> cores = collector.collect().nodes().stream()
                .collect(Collectors.toMap(NodeResource::getNodeId, NodeResource::getTotalCpu));
        PowerEfficiencyResponse eff = efficiency.evaluateCurrent();

        for (PowerEfficiencyResponse.NodeEfficiency n : eff.nodes()) {
            assertEquals(cores.get(n.nodeId()), n.totalCpu(), n.nodeId() + " 코어 수는 수집 값");
            if (n.evaluable()) {
                // 시각과 무관한 성질 — 같은 응답의 사용률 · 전력으로 다시 계산한 값과 같다
                double expected = Math.round(n.cpuUsagePercent() * n.totalCpu() * 10.0 / n.powerWatts() * 10000.0) / 10000.0;
                assertEquals(expected, n.usedCoresPerKw(), 1e-9, n.nodeId());
            } else {
                assertNull(n.usedCoresPerKw(), n.nodeId());
                assertNull(n.coreRank(), n.nodeId());
            }
        }
        assertEquals(Set.of(1, 2, 3, 4, 5), eff.nodes().stream().map(PowerEfficiencyResponse.NodeEfficiency::coreRank)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()), "계측 5대가 1~5 를 한 번씩");
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
        // 배포 서버는 노드 1대·전력 0이라 이 경로를 실값으로 볼 수 없다. 가상 클러스터가 그 자리를 대신한다 —
        // IPMI 있음 5대·없음 3대라 "일부만 계측"이라는 실환경 모양을 그대로 만든다.
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

    @DisplayName("조회 간격을 크게 줘도 에너지가 나온다")
    @Test
    void a_coarse_step_still_yields_energy() {
        // 허용 간격을 5분 하한으로 고정하면 1시간 간격 조회가 통째로 구멍으로 잘려 값이 전부 비고,
        // 사유는 "표본이 모자라다"는 엉뚱한 까닭을 낸다. 7일을 1시간으로 보는 것은 정상적인 사용이다.
        PowerOptimizationTrendResponse.Energy coarse = optimization.trend(null, null, "1h").energy();

        assertNotNull(coarse.consumedKwh(), "간격이 커졌다고 에너지가 사라지면 안 된다");
        assertTrue(coarse.consumedKwh() > 0);
        assertEquals(0, coarse.gaps(), "고른 표본인데 끊긴 것으로 세면 운영자가 수집기를 의심한다");
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
        // 두 계산이 실제로 달라지는 것은 시리즈 중간이 비는 경우이고, 그 자리는 IntervalEnergyTest가 값으로 확인한다.
        assertEquals(Math.round(fromPoints * 10), Math.round(t.energy().consumedKwh() * 10),
                "구간 에너지와 지점 적분의 눈금이 어긋난다");
    }

    @DisplayName("MDC 필터 — 세 MDC 추세를 더하면 클러스터 추세와 같다(노드 수 · 총 전력 · 회수 가능 전력 · 소비 에너지)")
    @Test
    void mdc_trends_add_up_to_cluster() throws Exception {
        // 배포 서버는 MDC 하나·전력 0이라 MDC별 값과 합을 볼 수 없다. 가상 클러스터(MDC 3곳)가 그 자리를 대신한다.
        String from = "2026-11-01T00:00:00Z", to = "2026-11-01T02:00:00Z";
        JsonNode all = trendData(from, to, null);
        List<JsonNode> mdcs = List.of(trendData(from, to, "mdc-seoul"), trendData(from, to, "mdc-busan"),
                trendData(from, to, "mdc-daejeon"));

        int n = all.get("points").size();
        assertEquals(25, n, "2시간/5분 + 1");
        for (int i = 0; i < n; i++) {
            JsonNode c = all.get("points").get(i);
            int nodes = 0;
            double watts = 0, reclaimable = 0;
            for (JsonNode m : mdcs) {
                JsonNode p = m.get("points").get(i);
                assertEquals(c.get("at").asString(), p.get("at").asString());
                nodes += p.get("totalNodes").asInt();
                watts += p.get("totalPowerWatts").isNull() ? 0 : p.get("totalPowerWatts").asDouble();
                reclaimable += p.get("reclaimableIdleWatts").isNull() ? 0 : p.get("reclaimableIdleWatts").asDouble();
            }
            assertEquals(8, c.get("totalNodes").asInt());
            assertEquals(8, nodes, "MDC 마다 그 MDC 노드만 — 필터를 무시하면 24");
            // 지점 전력은 소수 2자리로 반올림되므로 세 MDC 값과 클러스터 값의 반올림 오차(각 0.005)를 허용한다. kWh는 3자리라 0.002.
            assertEquals(c.get("totalPowerWatts").asDouble(), watts, 0.03);
            // 이 판의 미계측 3대는 늘 가동(CPU 최저 10%)이라 정격 대체값이 들지 않아 회수 가능 전력도 더해진다.
            assertEquals(c.get("reclaimableIdleWatts").asDouble(), reclaimable, 0.03);
        }
        double consumed = 0;
        for (JsonNode m : mdcs) {
            JsonNode kwh = m.get("energy").get("consumedKwh");
            consumed += kwh.isNull() ? 0 : kwh.asDouble();
        }
        assertEquals(all.get("energy").get("consumedKwh").asDouble(), consumed, 0.002);
        assertFalse(mdcs.get(2).get("points").get(0).get("measured").asBoolean(), "IPMI 없는 두 대뿐인 mdc-daejeon 은 미계측");
    }

    @DisplayName("MDC 필터 — 없는 MDC 는 200 이고 응답 6필드 그대로 빈 지점과 사유를 낸다")
    @Test
    void unknown_mdc_is_200_with_reason() throws Exception {
        JsonNode d = trendData("2026-11-01T00:00:00Z", "2026-11-01T02:00:00Z", "mdc-nowhere");

        assertEquals(new TreeSet<>(List.of("from", "to", "step", "points", "message", "energy")), new TreeSet<>(d.propertyNames()),
                "mdcId 를 응답에 다시 싣지 않는다");
        assertTrue(d.get("points").isEmpty(), "필터를 무시하면 8대 지점이 나온다");
        assertTrue(d.get("message").asString().contains("그 MDC 로 수집된 노드의 CPU 메트릭이 없습니다"), d.get("message").asString());
        assertTrue(d.get("energy").isNull());
    }

    private JsonNode trendData(String from, String to, String mdcId) throws Exception {
        var request = get("/api/v1/scheduler/optimization/power/trend").param("from", from).param("to", to).param("step", "5m");
        if (mdcId != null) request = request.param("mdcId", mdcId);
        return objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
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
        // 총량 모름 노드(daejeon-01, diskTotalGb 0)는 검사 없이 적격이 되고 그 사실이 상세에 남는다 — 나머지는 검사 적용
        java.util.Map<String, Double> byNode = new java.util.HashMap<>();
        d.candidateScores().forEach(s -> byNode.put(s.nodeId(), s.details().get("디스크검사")));
        assertEquals(0.0, byNode.get("daejeon-01:9100"), "수집기가 총량 0 을 그대로 들고 와 검사가 적용되지 않는다");
        assertEquals(1.0, byNode.get("seoul-01:9100"));
        assertEquals(1.0, byNode.get("daejeon-02:9100"), "부족으로 걸린 노드도 검사 대상이었다");
        assertEquals(8, byNode.values().stream().filter(java.util.Objects::nonNull).count(), "요구가 있는 배치라 모든 후보에 키가 있다");
    }

    @DisplayName("후보 API — 모의 저장소의 DCGM 2·IPMI 5 시리즈가 호스트명 일치로 노드에 붙고, 원장 행이 있는 노드는 원장 배정으로 보인다")
    @Test
    void exporter_instance_candidates_from_synthetic_store() throws Exception {
        JsonNode d = objectMapper.readTree(mockMvc.perform(get("/api/v1/scheduler/node-exporter-mappings/candidates"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");

        assertTrue(d.get("unavailable").isEmpty(), "모의 저장소는 세 질의에 전부 답한다: " + d.get("unavailable"));
        assertEquals(8, d.get("nodeInstances").size());
        assertEquals(2, d.get("dcgm").size(), "GPU 노드 2대의 DCGM 시리즈: " + d.get("dcgm"));
        assertEquals(5, d.get("ipmi").size(), "IPMI 있는 5대: " + d.get("ipmi"));
        Set<String> candidateFields = new TreeSet<>(List.of("instance", "host", "gpuCount", "assignedNodeId", "assignedBy",
                "claimedByNodeIds", "ledgerNodeIds"));
        Set<String> dcgmInstances = new TreeSet<>();
        d.get("dcgm").forEach(cand -> {
            assertEquals(candidateFields, new TreeSet<>(cand.propertyNames()), "후보 한 줄은 7필드");
            assertEquals(1, cand.get("claimedByNodeIds").size(), "한 노드만 이 시리즈를 얻는다: " + cand);
            dcgmInstances.add(cand.get("instance").asString());
            assertTrue(Set.of("HOSTNAME", "LEDGER").contains(cand.get("assignedBy").asString()),
                    "포트만 다른 배치는 호스트명으로, 다른 시험이 남긴 원장 행이 있으면 원장으로 붙는다: " + cand);
            assertEquals(cand.get("host").asString() + ":9100", cand.get("assignedNodeId").asString());
            assertTrue(cand.get("gpuCount").asInt() > 0);
        });
        assertEquals(Set.of("seoul-02:9400", "busan-03:9400"), dcgmInstances);
        d.get("ipmi").forEach(cand -> {
            assertTrue(cand.get("gpuCount").isNull(), "IPMI 후보에는 카드 수가 없다");
            assertTrue(Set.of("HOSTNAME", "LEDGER").contains(cand.get("assignedBy").asString()), cand.toString());
        });
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

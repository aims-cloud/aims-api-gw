package com.innogrid.aims.scheduler.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;
import com.innogrid.aims.scheduler.service.PowerOptimizationService;
import com.innogrid.aims.scheduler.service.SchedulingService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static com.innogrid.aims.scheduler.service.SchedulingServiceBuilder.aSchedulingService;
import static org.mockito.Mockito.when;

/**
 * 합성 클러스터 시나리오 — 실효 점수와 수집 제외 수정이 세 엔진과 수집기에서 함께 동작하는지.
 *
 * <p>배포 VM 은 노드 1대라 "여러 노드 사이의 분산"을 실환경에서 볼 수 없다. 합성 데이터가
 * 유일한 수단이다. 각 시나리오는 <b>입력 → 예상 출력</b>을 주석에 적고 표로 출력한다.</p>
 */
class EffectiveScoringScenarioTest {

    private final SchedulerProperties properties = new SchedulerProperties();

    /**
     * 시나리오 1 — 부하가 다른 4대에 2코어 워크로드 8건을 한 실행에서 연속 배치.
     *
     * <pre>
     * 입력   A cpu10% · B 20% · C 30% · D 40%  (mem 20%, 전력 200~350W/500W, 온도 45~60°C)
     * 예상   세 엔진 모두 8건 성공. weighted·temperature 는 3대 이상에 나눠 담고
     *        한 노드가 4건을 넘지 않는다 — 수정 전에는 첫 선택 노드로 [A,A,A,…] 몰렸다.
     * </pre>
     */
    @DisplayName("시나리오1 다건 연속 배치가 세 엔진에서 모두 분산된다")
    @Test
    void scenario1_spread_across_engines() {
        Map<String, Map<String, Integer>> table = new LinkedHashMap<>();

        for (SchedulingEngine engine : List.of(new WeightedScoreScheduler(),
                new TemperatureAwareScheduler(), new PowerAwareScheduler())) {
            PlacementContext context = PlacementContext.of(mixedCluster(), policy());
            Map<String, Integer> placed = new LinkedHashMap<>();
            for (NodeResource n : context.nodes()) placed.put(n.getNodeId(), 0);

            for (int i = 0; i < 8; i++) {
                Workload w = new Workload("w" + i, 2, 1024);
                SchedulingResult r = engine.schedule(w, context);
                assertTrue(r.success(), engine.getId() + " 배치 " + i + " 실패: " + r.reason());
                context.reserve(r.selectedNodeId(), w);
                placed.merge(r.selectedNodeId(), 1, Integer::sum);
            }
            table.put(engine.getId(), placed);
        }

        print("시나리오 1 — 4대 × 2코어 8건 연속 배치 (노드별 배치 수)", table);

        for (String engine : List.of("weighted", "temperature")) {
            Map<String, Integer> placed = table.get(engine);
            long used = placed.values().stream().filter(c -> c > 0).count();
            int max = placed.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            assertTrue(used >= 3, engine + ": 3대 이상에 분산돼야 한다 — " + placed);
            assertTrue(max <= 4, engine + ": 한 노드에 4건 넘게 몰리면 안 된다 — " + placed);
        }
    }

    /**
     * 시나리오 2 — IPMI 가 섞인 클러스터. 자원은 전부 같고 전력·온도 계측 여부만 다르다.
     *
     * <pre>
     * 입력   M1(200W·40°C) · M2(220W·45°C) 계측  /  U1 · U2 미계측(0W·0°C).  1코어 6건, weighted
     * 예상   첫 선택은 계측 노드 M1 (여유 60%·46.7% 는 중립 0.5 보다 낫다).
     *        계측 노드가 합쳐 2건 이상 받는다 — 수정 전에는 미계측이 만점(1.0·1.0)이라 6건 전부 U 로.
     * </pre>
     */
    @DisplayName("시나리오2 IPMI 혼합 클러스터에서 미계측 노드가 독식하지 않는다")
    @Test
    void scenario2_unmeasured_no_monopoly() {
        List<NodeResource> nodes = List.of(
                node("M1", 30, 40, 200, 40), node("M2", 30, 40, 220, 45),
                node("U1", 30, 40, 0, 0), node("U2", 30, 40, 0, 0));
        PlacementContext context = PlacementContext.of(nodes, policy());
        WeightedScoreScheduler engine = new WeightedScoreScheduler();

        List<String> order = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Workload w = new Workload("w" + i, 1, 512);
            SchedulingResult r = engine.schedule(w, context);
            assertTrue(r.success());
            context.reserve(r.selectedNodeId(), w);
            order.add(r.selectedNodeId());
        }

        System.out.println("\n[시나리오 2 — IPMI 혼합] 배치 순서: " + order);

        long measured = order.stream().filter(id -> id.startsWith("M")).count();
        assertEquals("M1", order.get(0), "첫 선택은 계측된 양호 노드여야 한다");
        assertTrue(measured >= 2, "계측 노드도 배치를 받아야 한다 — 미계측이 독식하면 0건: " + order);
    }

    /**
     * 시나리오 3 — 수집기부터 엔진까지. 3대 중 1대의 CPU 질의가 실패한다.
     *
     * <pre>
     * 입력   n1 정상 · n2 CPU 질의 예외(HTTP 400) · n3 정상.  weighted 로 2코어 4건
     * 예상   수집 결과 2대(n2 제외). 4건 전부 성공하며 n2 는 한 번도 선택되지 않는다.
     *        수정 전에는 n2 가 "CPU 0%" 로 들어와 첫 선택을 독식했다.
     * </pre>
     */
    @DisplayName("시나리오3 수집 실패 노드는 후보에서 빠지고 나머지로 배치된다")
    @Test
    void scenario3_failed_node_dropped() {
        VictoriaMetricsClient vm = mock(VictoriaMetricsClient.class);
        when(vm.query(startsWith("up{"))).thenReturn(Map.of("data", Map.of("result", List.of(
                up("n1:9100"), up("n2:9100"), up("n3:9100")))));
        when(vm.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.contains("DCGM")) return List.of();
            if (q.contains("n2:9100") && q.contains("irate(node_cpu")) throw new RuntimeException("HTTP 400");
            if (q.contains("irate(node_cpu")) return vector(q.contains("n1") ? 20.0 : 40.0);
            if (q.contains("count(node_cpu")) return vector(8);
            if (q.contains("MemTotal")) return vector(16e9);
            if (q.contains("MemAvailable")) return vector(8e9);
            return List.of();
        });
        when(vm.queryScalar(anyString())).thenReturn(0.0);

        NodeResourceCollector collector = new NodeResourceCollector(vm, properties, new PowerModel(properties));
        List<NodeResource> collected = collector.collectAll();
        List<String> ids = collected.stream().map(NodeResource::getNodeId).toList();

        assertEquals(List.of("n1:9100", "n3:9100"), ids, "CPU 를 알 수 없는 n2 는 수집되지 않아야 한다");

        PlacementContext context = PlacementContext.of(collected, policy());
        WeightedScoreScheduler engine = new WeightedScoreScheduler();
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Workload w = new Workload("w" + i, 2, 1024);
            SchedulingResult r = engine.schedule(w, context);
            assertTrue(r.success(), r.reason());
            context.reserve(r.selectedNodeId(), w);
            order.add(r.selectedNodeId());
        }

        System.out.println("\n[시나리오 3 — 부분 수집 실패] 수집=" + ids + " 배치 순서=" + order);
        assertFalse(order.contains("n2:9100"));
    }

    /**
     * 시나리오 4 — 수집기부터 진단 응답까지. 3대 중 1대가 제외된다.
     *
     * <pre>
     * 입력   n1 정상 · n2 CPU 질의 예외 · n3 정상.  실제 SchedulingService → PowerOptimizationService
     * 예상   cluster.totalNodes=2 (판단 가능한 노드), excludedNodes=1,
     *        excluded[0] = n2:9100 / "핵심 메트릭 판단 불가: cpu사용률 …"
     *        — 수정 전에는 totalNodes=3 에 n2 가 CPU 0% 의 유휴 노드로 들어갔다(g4 이전),
     *        g4 직후에는 totalNodes=2 만 보여 1대가 조용히 사라졌다.
     * </pre>
     */
    @DisplayName("시나리오4 제외 노드는 진단 응답에 수와 사유로 드러난다")
    @Test
    void scenario4_exclusions_in_diagnostics() {
        SchedulingService schedulingService = realSchedulingService(vmWithBrokenCpu("n2:9100"));
        PowerOptimizationService diagnosis =
                new PowerOptimizationService(schedulingService, new PowerModel(properties), properties);

        var r = diagnosis.diagnoseCurrent();

        System.out.println("\n[시나리오 4 — 진단 응답] totalNodes=" + r.cluster().totalNodes()
                + " excludedNodes=" + r.cluster().excludedNodes()
                + " excluded=" + r.cluster().excluded());
        assertEquals(2, r.cluster().totalNodes());
        assertEquals(1, r.cluster().excludedNodes());
        assertEquals("n2:9100", r.cluster().excluded().get(0).nodeId());
        assertTrue(r.cluster().excluded().get(0).reason().contains("cpu사용률"));
    }

    /**
     * 시나리오 5 — 메트릭 저장소가 CPU 를 전부 못 준다(전 노드 제외).
     *
     * <pre>
     * 입력   n1·n2·n3 모두 CPU 질의 예외.  dryRun(weighted) + 진단
     * 예상   dryRun 사유 = "활성 노드가 없습니다 — 핵심 메트릭을 알 수 없어 제외한 노드 3대: n1:9100, n2:9100, n3:9100"
     *        진단 totalNodes=0, excludedNodes=3
     *        — "활성 노드가 없습니다"만으로는 노드가 꺼진 것인지 저장소가 아픈 것인지 구분할 수 없었다.
     * </pre>
     */
    @DisplayName("시나리오5 전 노드가 제외되면 실패 사유가 원인을 말한다")
    @Test
    void scenario5_all_excluded_reason() {
        SchedulingService schedulingService = realSchedulingService(vmWithBrokenCpu("n1:9100", "n2:9100", "n3:9100"));

        SchedulingResult dry = schedulingService.dryRun(1L, "weighted");
        var r = new PowerOptimizationService(schedulingService, new PowerModel(properties), properties)
                .diagnoseCurrent();

        System.out.println("\n[시나리오 5 — 전 노드 제외] dryRun 사유: " + dry.reason()
                + "\n   진단 totalNodes=" + r.cluster().totalNodes() + " excludedNodes=" + r.cluster().excludedNodes());
        assertFalse(dry.success());
        assertEquals("활성 노드가 없습니다 — 핵심 메트릭을 알 수 없어 제외한 노드 3대: n1:9100, n2:9100, n3:9100",
                dry.reason());
        assertEquals(0, r.cluster().totalNodes());
        assertEquals(3, r.cluster().excludedNodes());
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private static SchedulingPolicy policy() {
        return new SchedulingPolicy("test", "test");   // maxTemperature 75, maxCpu 80
    }

    /** 3대(n1·n2·n3) 중 지정한 노드의 CPU 질의만 예외를 내는 메트릭 저장소. */
    private static VictoriaMetricsClient vmWithBrokenCpu(String... brokenInstances) {
        java.util.Set<String> broken = java.util.Set.of(brokenInstances);
        VictoriaMetricsClient vm = mock(VictoriaMetricsClient.class);
        when(vm.query(startsWith("up{"))).thenReturn(Map.of("data", Map.of("result", List.of(
                up("n1:9100"), up("n2:9100"), up("n3:9100")))));
        when(vm.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.contains("DCGM")) return List.of();
            if (q.contains("irate(node_cpu")) {
                for (String b : broken) if (q.contains(b)) throw new RuntimeException("HTTP 400");
                return vector(20.0);
            }
            if (q.contains("count(node_cpu")) return vector(8);
            if (q.contains("MemTotal")) return vector(16e9);
            if (q.contains("MemAvailable")) return vector(8e9);
            return List.of();
        });
        when(vm.queryScalar(anyString())).thenReturn(0.0);
        return vm;
    }

    /** 저장소만 가짜인 실제 SchedulingService. DB 는 기본 정책·워크로드 1건만 돌려준다. */
    private SchedulingService realSchedulingService(VictoriaMetricsClient vm) {
        var policyRepository = mock(com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository.class);
        var workloadRepository = mock(com.innogrid.aims.scheduler.repository.WorkloadRepository.class);
        when(policyRepository.findFirstByActiveTrueAndResourceTypeIsNull())
                .thenReturn(java.util.Optional.of(policy()));
        when(workloadRepository.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.of(new Workload("w", 1, 512)));
        when(workloadRepository.findByStatusIn(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of());

        return aSchedulingService()
                .workloads(workloadRepository)
                .policies(policyRepository)
                .collector(new NodeResourceCollector(vm, properties, new PowerModel(properties)))
                .engines(new EngineRegistry(List.of(new WeightedScoreScheduler())))
                .properties(properties)
                .build();
    }

    /** 부하가 다른 4대. 호출마다 새 객체 — 예약이 쌓이는 가변 객체라 엔진 간에 공유하지 않는다. */
    private static List<NodeResource> mixedCluster() {
        return List.of(
                node("A", 10, 20, 200, 45),
                node("B", 20, 20, 250, 50),
                node("C", 30, 20, 300, 55),
                node("D", 40, 20, 350, 60));
    }

    private static NodeResource node(String id, double cpu, double mem, double power, double temp) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, cpu, mem, 40, power, 500, temp, 1000, 500);
    }

    private static Map<String, Object> up(String instance) {
        return Map.of("metric", Map.of("instance", instance, "mdc_id", "mdc-01"), "value", List.of(0, "1"));
    }

    private static List<Map<String, Object>> vector(double value) {
        return List.of(Map.of("metric", Map.of(), "value", List.of(0, Double.toString(value))));
    }

    private static void print(String title, Map<String, Map<String, Integer>> table) {
        StringBuilder sb = new StringBuilder("\n[" + title + "]\n");
        sb.append(String.format("  %-12s", "engine"));
        table.values().iterator().next().keySet().forEach(n -> sb.append(String.format("%6s", n)));
        sb.append('\n');
        table.forEach((engine, row) -> {
            sb.append(String.format("  %-12s", engine));
            row.values().forEach(c -> sb.append(String.format("%6d", c)));
            sb.append('\n');
        });
        System.out.println(sb);
    }
}

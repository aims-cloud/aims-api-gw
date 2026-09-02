package com.innogrid.aims.scheduler.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 합성 클러스터 시나리오 — 단계 9 의 수정이 세 엔진과 수집기에서 함께 동작하는지.
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
    @Test
    void 시나리오1_다건_연속_배치가_세_엔진에서_모두_분산된다() {
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
    @Test
    void 시나리오2_IPMI_혼합_클러스터에서_미계측_노드가_독식하지_않는다() {
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
    @Test
    void 시나리오3_수집_실패_노드는_후보에서_빠지고_나머지로_배치된다() {
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

    // ── 픽스처 ──────────────────────────────────────────────────

    private static SchedulingPolicy policy() {
        return new SchedulingPolicy("test", "test");   // maxTemperature 75, maxCpu 80
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

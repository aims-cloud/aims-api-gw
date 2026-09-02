package com.innogrid.aims.scheduler.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 수집기가 "조회 실패·시리즈 없음"과 "값 0"을 구분하는지.
 *
 * <p>배포 저장소 실증(2026-09-02): 없는 instance 의 CPU 질의 → result 0건, 문법 오류 →
 * HTTP 400. 둘 다 예전 코드에서는 CPU 0% 로 읽혔다. 전력은 운영에서 실제로 이 상태다.</p>
 */
class NodeResourceCollectorTest {

    private static final String N1 = "n1:9100";
    private static final String N2 = "n2:9100";

    private VictoriaMetricsClient vm;
    private NodeResourceCollector collector;

    /** 질의 문자열의 일부 → 응답. 값이 null 이면 빈 결과, RuntimeException 이면 예외. */
    private final Map<String, Object> answers = new HashMap<>();

    @BeforeEach
    void setUp() {
        vm = mock(VictoriaMetricsClient.class);
        SchedulerProperties props = new SchedulerProperties();
        collector = new NodeResourceCollector(vm, props, new PowerModel(props));

        when(vm.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            for (Map.Entry<String, Object> e : answers.entrySet()) {
                if (q.contains(e.getKey())) {
                    if (e.getValue() instanceof RuntimeException ex) throw ex;
                    if (e.getValue() == null) return List.of();
                    return vector(((Number) e.getValue()).doubleValue());
                }
            }
            return List.of();                       // 정의하지 않은 질의(GPU 등)는 빈 결과
        });
        when(vm.queryScalar(anyString())).thenReturn(0.0);   // 부가 메트릭은 전부 0(IPMI 없음)
    }

    @Test
    void 핵심_메트릭이_모두_있으면_실측값으로_노드를_만든다() {
        up(N1);
        healthy(N1, 12.67, 2, 4_000_000_000d, 1_250_000_000d);

        List<NodeResource> nodes = collector.collectAll();

        assertEquals(1, nodes.size());
        NodeResource n = nodes.get(0);
        assertEquals(12.67, n.getCpuUsagePercent(), 1e-9);
        assertEquals(2, n.getTotalCpu());
        assertEquals(3814, n.getTotalMemoryMb());                    // 4e9 / 1024 / 1024
        assertEquals(68.75, n.getMemoryUsagePercent(), 1e-9);        // 1 - 1.25/4
        assertEquals(NodeResource.PowerSource.NONE, n.getPowerSource());
    }

    @Test
    void CPU_질의가_빈_결과면_노드를_수집하지_않는다() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        answers.put(cpuKey(N1), null);                               // 시리즈 없음

        assertTrue(collector.collectAll().isEmpty(),
                "예전 코드는 CPU 0% 의 빈 노드로 만들어 배치·진단을 오판시켰다");
    }

    @Test
    void 코어_수_질의가_예외면_노드를_수집하지_않는다() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        answers.put(coresKey(N1), new RuntimeException("HTTP 400"));

        assertTrue(collector.collectAll().isEmpty(),
                "예전 코드는 Math.max(0, 1) 로 1코어짜리 가짜 노드를 만들었다");
    }

    @Test
    void 메모리_질의가_없으면_노드를_수집하지_않는다() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        answers.put("node_memory_MemAvailable_bytes{instance=\"" + N1, null);

        assertTrue(collector.collectAll().isEmpty());
    }

    @Test
    void 한_노드가_빠져도_다른_노드는_정상_수집된다() {
        up(N1, N2);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        healthy(N2, 40.0, 8, 16e9, 8e9);
        answers.put(cpuKey(N1), new RuntimeException("timeout"));

        List<NodeResource> nodes = collector.collectAll();

        assertEquals(1, nodes.size());
        assertEquals(N2, nodes.get(0).getNodeId());
    }

    @Test
    void 전력_온도가_없어도_노드는_수집하고_플래그로_표시한다() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        // 전력·온도는 safeQuery(queryScalar) → 0.0 이 기본. 제외 대상이 아니다.

        List<NodeResource> nodes = collector.collectAll();

        assertEquals(1, nodes.size());
        assertEquals(0.0, nodes.get(0).getPowerWatts(), 1e-9);
        assertEquals(NodeResource.PowerSource.NONE, nodes.get(0).getPowerSource());
    }

    @Test
    void 실측_CPU_0퍼센트는_값이_있는_것이므로_수집한다() {
        up(N1);
        healthy(N1, 0.0, 2, 4e9, 1.25e9);   // 시리즈는 있고 값이 0

        List<NodeResource> nodes = collector.collectAll();

        assertEquals(1, nodes.size());
        assertEquals(0.0, nodes.get(0).getCpuUsagePercent(), 1e-9);
    }

    @Test
    void 제외된_노드는_어떤_메트릭을_몰랐는지와_함께_스냅샷에_남는다() {
        up(N1, N2);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        healthy(N2, 40.0, 8, 16e9, 8e9);
        answers.put(cpuKey(N1), null);                               // 시리즈 없음
        answers.put(coresKey(N1), new RuntimeException("HTTP 400"));

        NodeResourceCollector.Snapshot snapshot = collector.collect();

        assertEquals(List.of(N2), snapshot.nodes().stream().map(NodeResource::getNodeId).toList());
        assertEquals(1, snapshot.excluded().size());
        assertEquals(N1, snapshot.excluded().get(0).nodeId());
        assertEquals("mdc-01", snapshot.excluded().get(0).mdcId());
        assertEquals(List.of("cpu사용률", "코어수"), snapshot.excluded().get(0).missingMetrics());
    }

    @Test
    void 전부_정상이면_제외_목록이_빈다() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);

        NodeResourceCollector.Snapshot snapshot = collector.collect();

        assertEquals(1, snapshot.nodes().size());
        assertTrue(snapshot.excluded().isEmpty());
    }

    @Test
    void collectAll_은_스냅샷의_후보_목록과_같다() {
        up(N1, N2);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        healthy(N2, 40.0, 8, 16e9, 8e9);
        answers.put(cpuKey(N2), null);

        assertEquals(List.of(N1), collector.collectAll().stream().map(NodeResource::getNodeId).toList());
    }

    @Test
    void 디스크_총량과_가용량으로_총량_GB_와_사용률을_만든다() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        when(vm.queryScalar(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.startsWith("node_filesystem_size_bytes")) return 500.0 * 1024 * 1024 * 1024;
            if (q.startsWith("node_filesystem_avail_bytes")) return 300.0 * 1024 * 1024 * 1024;
            return 0.0;
        });

        NodeResource n = collector.collectAll().get(0);

        assertEquals(500, n.diskCapacityGb());
        assertEquals(40.0, n.getDiskUsagePercent(), 1e-9);      // 1 - 300/500
        assertEquals(300, n.availableDiskGb());
    }

    @Test
    void 디스크_총량을_못_얻어도_노드는_수집되고_총량은_0_모름_이다() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        // queryScalar 기본 스텁이 0.0 — 시리즈 없음과 같다

        List<NodeResource> nodes = collector.collectAll();

        assertEquals(1, nodes.size(), "디스크는 부가 메트릭이라 제외 사유가 아니다");
        assertEquals(0, nodes.get(0).diskCapacityGb());
        assertEquals(0.0, nodes.get(0).getDiskUsagePercent(), 1e-9);
    }

    @Test
    void up_질의가_실패하면_빈_목록이다() {
        when(vm.query(startsWith("up{"))).thenThrow(new RuntimeException("connection refused"));

        assertTrue(collector.collectAll().isEmpty());
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private void up(String... instances) {
        List<Map<String, Object>> result = java.util.Arrays.stream(instances)
                .map(i -> Map.<String, Object>of("metric", Map.of("instance", i, "mdc_id", "mdc-01"),
                        "value", List.of(0, "1")))
                .toList();
        when(vm.query(startsWith("up{"))).thenReturn(Map.of("data", Map.of("result", result)));
    }

    private void healthy(String instance, double cpu, int cores, double memTotal, double memAvail) {
        answers.put(cpuKey(instance), cpu);
        answers.put(coresKey(instance), cores);
        answers.put("node_memory_MemTotal_bytes{instance=\"" + instance, memTotal);
        answers.put("node_memory_MemAvailable_bytes{instance=\"" + instance, memAvail);
    }

    private static String cpuKey(String instance) {
        return "irate(node_cpu_seconds_total{mode=\"idle\",instance=\"" + instance;
    }

    private static String coresKey(String instance) {
        return "count(node_cpu_seconds_total{mode=\"idle\",instance=\"" + instance;
    }

    private static List<Map<String, Object>> vector(double value) {
        return List.of(Map.of("metric", Map.of(), "value", List.of(0, Double.toString(value))));
    }
}

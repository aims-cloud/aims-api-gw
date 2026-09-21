package com.innogrid.aims.scheduler.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
                    if (e.getValue() instanceof List<?> rows) return rows;
                    return vector(((Number) e.getValue()).doubleValue());
                }
            }
            return List.of();                       // 정의하지 않은 질의(GPU 등)는 빈 결과
        });
        when(vm.queryScalar(anyString())).thenReturn(0.0);   // 부가 메트릭은 전부 0(IPMI 없음)
    }

    @DisplayName("핵심 메트릭이 모두 있으면 실측값으로 노드를 만든다")
    @Test
    void all_core_metrics_build_node() {
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

    @DisplayName("CPU 질의가 빈 결과면 노드를 수집하지 않는다")
    @Test
    void empty_cpu_query_skips_node() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        answers.put(cpuKey(N1), null);                               // 시리즈 없음

        assertTrue(collector.collectAll().isEmpty(),
                "예전 코드는 CPU 0% 의 빈 노드로 만들어 배치·진단을 오판시켰다");
    }

    @DisplayName("코어 수 질의가 예외면 노드를 수집하지 않는다")
    @Test
    void core_count_exception_skips_node() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        answers.put(coresKey(N1), new RuntimeException("HTTP 400"));

        assertTrue(collector.collectAll().isEmpty(),
                "예전 코드는 Math.max(0, 1) 로 1코어짜리 가짜 노드를 만들었다");
    }

    @DisplayName("메모리 질의가 없으면 노드를 수집하지 않는다")
    @Test
    void missing_memory_skips_node() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        answers.put("node_memory_MemAvailable_bytes{instance=\"" + N1, null);

        assertTrue(collector.collectAll().isEmpty());
    }

    @DisplayName("한 노드가 빠져도 다른 노드는 정상 수집된다")
    @Test
    void one_node_dropped_others_collected() {
        up(N1, N2);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        healthy(N2, 40.0, 8, 16e9, 8e9);
        answers.put(cpuKey(N1), new RuntimeException("timeout"));

        List<NodeResource> nodes = collector.collectAll();

        assertEquals(1, nodes.size());
        assertEquals(N2, nodes.get(0).getNodeId());
    }

    @DisplayName("전력 온도가 없어도 노드는 수집하고 플래그로 표시한다")
    @Test
    void no_power_thermal_node_flagged() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        // 전력·온도는 safeQuery(queryScalar) → 0.0 이 기본. 제외 대상이 아니다.

        List<NodeResource> nodes = collector.collectAll();

        assertEquals(1, nodes.size());
        assertEquals(0.0, nodes.get(0).getPowerWatts(), 1e-9);
        assertEquals(NodeResource.PowerSource.NONE, nodes.get(0).getPowerSource());
    }

    @DisplayName("실측 CPU 0퍼센트는 값이 있는 것이므로 수집한다")
    @Test
    void cpu_0_percent_is_collected() {
        up(N1);
        healthy(N1, 0.0, 2, 4e9, 1.25e9);   // 시리즈는 있고 값이 0

        List<NodeResource> nodes = collector.collectAll();

        assertEquals(1, nodes.size());
        assertEquals(0.0, nodes.get(0).getCpuUsagePercent(), 1e-9);
    }

    @DisplayName("제외된 노드는 어떤 메트릭을 몰랐는지와 함께 스냅샷에 남는다")
    @Test
    void excluded_node_lists_missing_metrics() {
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

    @DisplayName("전부 정상이면 제외 목록이 빈다")
    @Test
    void all_healthy_excluded_list_empty() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);

        NodeResourceCollector.Snapshot snapshot = collector.collect();

        assertEquals(1, snapshot.nodes().size());
        assertTrue(snapshot.excluded().isEmpty());
    }

    @DisplayName("collectAll 은 스냅샷의 후보 목록과 같다")
    @Test
    void collect_all_matches_snapshot_candidates() {
        up(N1, N2);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        healthy(N2, 40.0, 8, 16e9, 8e9);
        answers.put(cpuKey(N2), null);

        assertEquals(List.of(N1), collector.collectAll().stream().map(NodeResource::getNodeId).toList());
    }

    @DisplayName("디스크 총량과 가용량으로 총량 GB 와 사용률을 만든다")
    @Test
    void disk_total_and_available_give_usage() {
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

    @DisplayName("디스크 총량을 못 얻어도 노드는 수집되고 총량은 0 모름 이다")
    @Test
    void missing_disk_total_gives_0_unknown() {
        up(N1);
        healthy(N1, 12.67, 2, 4e9, 1.25e9);
        // queryScalar 기본 스텁이 0.0 — 시리즈 없음과 같다

        List<NodeResource> nodes = collector.collectAll();

        assertEquals(1, nodes.size(), "디스크는 부가 메트릭이라 제외 사유가 아니다");
        assertEquals(0, nodes.get(0).diskCapacityGb());
        assertEquals(0.0, nodes.get(0).getDiskUsagePercent(), 1e-9);
    }

    @DisplayName("up 질의가 실패하면 빈 목록이다")
    @Test
    void up_query_failure_empty_list() {
        when(vm.query(startsWith("up{"))).thenThrow(new RuntimeException("connection refused"));

        assertTrue(collector.collectAll().isEmpty());
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    @DisplayName("GPU 노드는 DCGM instance 로 GPU 사용률·온도·전력을 받고, 섀시 전력·온도에는 섞지 않는다")
    @Test
    void gpu_node_gets_telemetry_by_dcgm_instance_without_touching_chassis_values() {
        up(N1);
        healthy(N1, 30, 8, 4e9, 2e9);
        answers.put("count by(instance) (DCGM_FI_DEV_GPU_UTIL)", instanceVector("n1:9400", 2));
        answers.put("avg by(instance) (DCGM_FI_DEV_GPU_UTIL)", instanceVector("n1:9400", 75.5));
        answers.put("max by(instance) (DCGM_FI_DEV_GPU_TEMP)", instanceVector("n1:9400", 71));
        answers.put("sum by(instance) (DCGM_FI_DEV_POWER_USAGE)", instanceVector("n1:9400", 410.25));

        NodeResource n = collector.collectAll().get(0);

        assertEquals(2, n.getTotalGpu(), "호스트명 일치로 n1:9400 이 이어진다");
        assertEquals(75.5, n.getGpuUsagePercent(), 1e-9);
        assertEquals(71.0, n.getGpuTemperatureCelsius(), 1e-9);
        assertEquals(410.25, n.getGpuPowerWatts(), 1e-9);
        assertEquals(0.0, n.getPowerWatts(), "IPMI 가 없으니 섀시 전력은 그대로 0 — GPU 전력을 더하지 않는다");
        assertEquals(0.0, n.getTemperatureCelsius(), "섀시 온도도 0 — GPU 온도와 섞지 않는다");
        assertEquals(NodeResource.PowerSource.NONE, n.getPowerSource());
        NodeResource copy = n.snapshot();
        assertEquals(410.25, copy.getGpuPowerWatts(), 1e-9, "사본에도 GPU 축이 옮겨진다");
        assertEquals(71.0, copy.getGpuTemperatureCelsius(), 1e-9);
    }

    @DisplayName("GPU 축은 클러스터 한 번씩 세 질의로 묻고 문자열은 avg·max·sum by(instance) 다 — 노드마다 묻지 않는다")
    @Test
    void gpu_telemetry_is_three_cluster_wide_queries_with_fixed_aggregations() {
        up(N1, N2);
        healthy(N1, 30, 8, 4e9, 2e9);
        healthy(N2, 30, 8, 4e9, 2e9);
        answers.put("count by(instance) (DCGM_FI_DEV_GPU_UTIL)", List.of(
                instanceVector("n1:9400", 2).get(0), instanceVector("n2:9400", 4).get(0)));
        List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(vm.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            sent.add(q);
            for (Map.Entry<String, Object> e : answers.entrySet()) {
                if (q.contains(e.getKey())) {
                    if (e.getValue() instanceof List<?> rows) return rows;
                    return vector(((Number) e.getValue()).doubleValue());
                }
            }
            return List.of();
        });

        collector.collectAll();

        assertEquals(1, sent.stream().filter("avg by(instance) (DCGM_FI_DEV_GPU_UTIL)"::equals).count(), "사용률은 카드 평균, 클러스터 1회");
        assertEquals(1, sent.stream().filter("max by(instance) (DCGM_FI_DEV_GPU_TEMP)"::equals).count(), "온도는 가장 뜨거운 카드, 클러스터 1회");
        assertEquals(1, sent.stream().filter("sum by(instance) (DCGM_FI_DEV_POWER_USAGE)"::equals).count(), "전력은 카드 합, 클러스터 1회");
        assertEquals(0, sent.stream().filter(q -> q.contains("DCGM") && q.contains("instance=\"")).count(), "노드별 DCGM 질의는 없다");
    }

    @DisplayName("GPU 시리즈가 없는 클러스터는 GPU 축 질의를 아예 던지지 않고 값은 0 이다")
    @Test
    void no_gpu_series_means_no_gpu_queries() {
        up(N1);
        healthy(N1, 30, 8, 4e9, 2e9);
        List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(vm.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            sent.add(q);
            for (Map.Entry<String, Object> e : answers.entrySet()) {
                if (q.contains(e.getKey()) && e.getValue() != null) return vector(((Number) e.getValue()).doubleValue());
            }
            return List.of();
        });

        NodeResource n = collector.collectAll().get(0);

        assertEquals(0, n.getTotalGpu());
        assertEquals(0.0, n.getGpuUsagePercent());
        assertEquals(0.0, n.getGpuTemperatureCelsius());
        assertEquals(0.0, n.getGpuPowerWatts());
        assertEquals(0, sent.stream().filter(q -> q.contains("DCGM_FI_DEV_GPU_TEMP") || q.contains("DCGM_FI_DEV_POWER_USAGE")
                || q.startsWith("avg by(instance) (DCGM")).count(), "GPU 개수 질의만 나가고 축 질의는 없다");
    }

    private static List<Map<String, Object>> instanceVector(String instance, double value) {
        return List.of(Map.of("metric", Map.of("instance", instance), "value", List.of(0, Double.toString(value))));
    }

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

package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 수집기의 시간 상한 — 저장소가 응답을 안 줄 때 그 노드만 "응답 없음"으로 빼고 나머지로 계속 가는가, 순서는 지키는가,
 * 전체 상한·대상 목록 상한이 동작하는가, 정상 경로는 예전과 같은가.
 *
 * <p>가짜 클라이언트는 instance 이름에 {@code slow} 가 든 질의에서만 래치를 기다린다(저장소 무응답 흉내).
 * 상한은 짧게(노드 150ms·전체 600ms·단건 150ms) 두어 테스트가 빨리 끝나게 한다.</p>
 */
class NodeResourceCollectorTimeoutTest {

    private static final String FAST_1 = "fast-1:9100", SLOW = "slow-1:9100", FAST_2 = "fast-2:9100";

    private VictoriaMetricsClient vm;
    private SchedulerProperties props;
    private NodeResourceCollector collector;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        vm = mock(VictoriaMetricsClient.class);
        props = new SchedulerProperties();
        props.getMetrics().setNodeTimeoutMs(150);
        props.getMetrics().setCollectTimeoutMs(600);
        props.getMetrics().setQueryTimeoutMs(150);
        props.getMetrics().setParallelism(4);
        collector = new NodeResourceCollector(vm, props, new PowerModel(props));

        when(vm.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.contains("slow")) blockUntilReleased();
            if (q.contains("DCGM")) return List.of();
            if (q.contains("node_cpu_seconds_total") && q.contains("count(")) return vector(4);
            if (q.contains("node_cpu_seconds_total")) return vector(20);
            if (q.contains("MemTotal")) return vector(8e9);
            if (q.contains("MemAvailable")) return vector(4e9);
            return List.of();
        });
        when(vm.queryScalar(anyString())).thenReturn(0.0);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
    }

    @Test
    void 응답_없는_노드만_제외하고_나머지는_순서대로_수집한다() {
        up(FAST_1, SLOW, FAST_2);
        long started = System.nanoTime();

        NodeResourceCollector.Snapshot snap = collector.collect();

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(List.of(FAST_1, FAST_2), snap.nodes().stream().map(NodeResource::getNodeId).toList(), "순서 보존");
        assertEquals(1, snap.excluded().size());
        ExcludedNode ex = snap.excluded().get(0);
        assertEquals(SLOW, ex.nodeId());
        assertTrue(ex.isTimeout());
        assertTrue(ex.reason().contains("응답 없음") && ex.reason().contains("150ms"), ex.reason());
        assertTrue(elapsedMs < 3000, "상한 근처에서 돌아온다: " + elapsedMs + "ms");
    }

    @Test
    void 정상_노드만_있으면_결과가_예전과_같다() {
        up(FAST_1, FAST_2);

        NodeResourceCollector.Snapshot snap = collector.collect();

        assertEquals(2, snap.nodes().size());
        assertTrue(snap.excluded().isEmpty());
        NodeResource n = snap.nodes().get(0);
        assertEquals(FAST_1, n.getNodeId());
        assertEquals(4, n.getTotalCpu());
        assertEquals(20.0, n.getCpuUsagePercent(), 1e-9);
        assertEquals(50.0, n.getMemoryUsagePercent(), 1e-9);
    }

    @Test
    void 전체_상한이_먼저_오면_끝나지_않은_노드를_전부_제외한다() {
        props.getMetrics().setNodeTimeoutMs(10_000);      // 노드 상한은 넉넉히, 전체 상한만 짧게
        props.getMetrics().setCollectTimeoutMs(300);
        up("slow-a:9100", "slow-b:9100", "slow-c:9100", "slow-d:9100", "slow-e:9100");   // 풀 4 보다 많다
        long started = System.nanoTime();

        NodeResourceCollector.Snapshot snap = collector.collect();

        assertTrue(snap.nodes().isEmpty());
        assertEquals(5, snap.excluded().size(), "시작도 못 한 노드도 응답 없음으로 제외");
        assertTrue(snap.excluded().stream().allMatch(ExcludedNode::isTimeout));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000);
    }

    @Test
    void 대상_목록_질의가_상한을_넘기면_빈_스냅샷이다() {
        when(vm.query(startsWith("up{"))).thenAnswer(inv -> { blockUntilReleased(); return Map.of(); });

        NodeResourceCollector.Snapshot snap = collector.collect();

        assertTrue(snap.nodes().isEmpty());
        assertTrue(snap.excluded().isEmpty(), "대상 목록을 모르니 제외 노드도 없다 — 지금까지의 '수집 실패' 경로와 같다");
    }

    @Test
    void 핵심_메트릭이_없는_노드는_여전히_판단_불가_사유로_제외된다() {
        up(FAST_1, "nometric-1:9100");
        when(vm.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.contains("nometric")) return List.of();
            if (q.contains("DCGM")) return List.of();
            if (q.contains("count(")) return vector(4);
            if (q.contains("node_cpu_seconds_total")) return vector(20);
            if (q.contains("MemTotal")) return vector(8e9);
            if (q.contains("MemAvailable")) return vector(4e9);
            return List.of();
        });

        NodeResourceCollector.Snapshot snap = collector.collect();

        assertEquals(1, snap.nodes().size());
        assertEquals(1, snap.excluded().size());
        assertFalse(snap.excluded().get(0).isTimeout());
        assertTrue(snap.excluded().get(0).reason().startsWith("핵심 메트릭 판단 불가"));
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private void up(String... instances) {
        List<Map<String, Object>> result = java.util.Arrays.stream(instances)
                .map(i -> Map.<String, Object>of("metric", Map.of("instance", i, "mdc_id", "mdc-01"), "value", List.of(0, "1")))
                .toList();
        when(vm.query(startsWith("up{"))).thenReturn(Map.of("data", Map.of("result", result)));
    }

    private void blockUntilReleased() {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<Map<String, Object>> vector(double value) {
        return List.of(Map.of("metric", Map.of(), "value", List.of(0, Double.toString(value))));
    }
}

package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 시나리오 — 노드 3대 중 1대의 메트릭 저장소 응답이 멈췄을 때, 스케줄러가 멈추지 않고 나머지로 배치하며 그 노드를
 * "응답 없음"으로 드러내는가. 수집기·배치 서비스·노드 조회를 실제 객체로 잇고 저장소만 가짜다.
 *
 * <pre>
 * 입력                                            예상
 * ① 배치(dryRun) — fast-1·slow-1·fast-2           성공, 후보 2대(fast-1·fast-2), 상한(150ms) 근처에서 응답
 * ② 노드 스냅샷 excludedNodes                       slow-1 1건, 사유 "메트릭 질의 응답 없음 (상한 150ms 초과 …)"
 * ③ 노드 단건 조회 slow-1                           404, 사유에 "수집 제외" + "응답 없음"
 * ④ 저장소 전체 무응답(대상 목록도 멈춤)             배치 실패 "활성 노드가 없습니다", 상한 안에 응답 (예전: 무한 대기)
 * ⑤ 저장소 복구                                    3대 전부 수집, 제외 0
 * </pre>
 */
class MetricsTimeoutScenarioTest {

    private static final String FAST_1 = "fast-1:9100", SLOW = "slow-1:9100", FAST_2 = "fast-2:9100";

    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean upHangs = false;
    private volatile boolean slowHangs = true;

    private VictoriaMetricsClient vm;
    private SchedulingService service;

    @BeforeEach
    void setUp() {
        vm = mock(VictoriaMetricsClient.class);
        SchedulerProperties props = new SchedulerProperties();
        props.getMetrics().setNodeTimeoutMs(150);
        props.getMetrics().setCollectTimeoutMs(600);
        props.getMetrics().setQueryTimeoutMs(150);
        NodeResourceCollector collector = new NodeResourceCollector(vm, props, new PowerModel(props));

        WorkloadRepository workloads = mock(WorkloadRepository.class);
        Workload w = new Workload("w", 1, 512);
        when(workloads.findById(anyLong())).thenReturn(Optional.of(w));
        SchedulingPolicyRepository policies = mock(SchedulingPolicyRepository.class);
        when(policies.findFirstByActiveTrueAndResourceTypeIsNull()).thenReturn(Optional.of(new SchedulingPolicy("default", "기본")));

        service = new SchedulingService(workloads, policies, collector, mock(PlacementService.class),
                new EngineRegistry(List.of(new WeightedScoreScheduler())), ConstraintEvaluator.withDefaults(), props);

        when(vm.query(startsWith("up{"))).thenAnswer(inv -> {
            if (upHangs) block();
            List<Map<String, Object>> result = new ArrayList<>();
            for (String i : List.of(FAST_1, SLOW, FAST_2)) {
                result.add(Map.of("metric", Map.of("instance", i, "mdc_id", "mdc-01"), "value", List.of(0, "1")));
            }
            return Map.of("data", Map.of("result", result));
        });
        when(vm.queryResults(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.contains("slow") && slowHangs) block();
            if (q.contains("DCGM")) return List.of();
            if (q.contains("count(")) return vector(4);
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

    @DisplayName("시나리오 저장소 한 노드 무응답에도 배치는 계속되고 그 노드는 응답 없음으로 드러난다")
    @Test
    void scenario_one_node_unresponsive() {
        StringBuilder table = new StringBuilder("\n| # | 입력 | 예상 | 실제 |\n|---|---|---|---|\n");

        // ① 배치
        long t0 = System.nanoTime();
        SchedulingResult r1 = service.dryRun(1L, "weighted");
        long ms1 = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        table.append(String.format("| ① | 배치 dryRun(3대, slow 1대 무응답) | 성공·후보 2대 | success=%s 후보=%d %dms |%n",
                r1.success(), r1.candidateScores().size(), ms1));
        assertTrue(r1.success());
        assertEquals(2, r1.candidateScores().size());
        assertTrue(ms1 < 3000, "상한 근처에서 응답: " + ms1 + "ms");

        // ② 스냅샷 제외 목록
        PlacementContext snap = service.nodeSnapshot();
        table.append(String.format("| ② | 스냅샷 excludedNodes | slow-1 응답 없음 | %s |%n",
                snap.excludedNodes().stream().map(e -> e.nodeId() + ": " + e.reason()).toList()));
        assertEquals(1, snap.excludedNodes().size());
        assertEquals(SLOW, snap.excludedNodes().get(0).nodeId());
        assertTrue(snap.excludedNodes().get(0).reason().contains("응답 없음"));
        assertEquals(List.of(FAST_1, FAST_2), snap.nodes().stream().map(n -> n.getNodeId()).toList(), "순서 보존");

        // ③ 단건 조회 404
        BusinessException e3 = assertThrows(BusinessException.class, () -> service.getNodeResource(SLOW));
        table.append(String.format("| ③ | 노드 단건 조회 slow-1 | 404 수집 제외·응답 없음 | %s %s |%n", e3.getStatus().value(), e3.getMessage()));
        assertEquals(404, e3.getStatus().value());
        assertTrue(e3.getMessage().contains("수집 제외") && e3.getMessage().contains("응답 없음"), e3.getMessage());

        // ④ 저장소 전체 무응답
        upHangs = true;
        long t4 = System.nanoTime();
        SchedulingResult r4 = service.dryRun(1L, "weighted");
        long ms4 = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t4);
        table.append(String.format("| ④ | 저장소 전체 무응답 | 실패 '활성 노드가 없습니다', 상한 안 | success=%s '%s' %dms |%n",
                r4.success(), r4.reason(), ms4));
        assertFalse(r4.success());
        assertTrue(r4.reason().contains("활성 노드가 없습니다"));
        assertTrue(ms4 < 3000, "예전에는 무한 대기: " + ms4 + "ms");

        // ⑤ 복구
        upHangs = false; slowHangs = false;
        PlacementContext snap5 = service.nodeSnapshot();
        table.append(String.format("| ⑤ | 저장소 복구 | 3대 수집·제외 0 | 노드=%d 제외=%d |%n", snap5.nodes().size(), snap5.excludedNodes().size()));
        assertEquals(3, snap5.nodes().size());
        assertTrue(snap5.excludedNodes().isEmpty());

        System.out.println(table);
    }

    private void block() {
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

package com.innogrid.aims.scheduler.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkContexts;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.Board;
import com.innogrid.aims.scheduler.dto.PlacementBenchmarkResponse.TemperatureLimitCheck;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.Exclusion;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 배치 전략 비교 판의 노드 온도와 두 온도 상한(온도 상한 열 65℃ · 기준 정책 75℃)을 대조한다.
 *
 * <p>온도는 엔진이 실제로 보는 배치 전 스냅샷({@link PlacementBenchmarkService#nodesAt})에서 읽는다. 미계측 노드는 이 스냅샷에서
 * 0℃ 이고, 엔진의 현재 온도 검사는 상한을 <b>넘을 때만</b> 거른다. 스프링을 띄우지 않는다.</p>
 */
class BenchmarkTemperatureLimitCheckTest {

    private static final BenchmarkClusters CLUSTERS = new BenchmarkClusters();
    private static final double LOWERED = BenchmarkRules.LOWERED_TEMPERATURE_LIMIT_CELSIUS;
    private static final double POLICY = BenchmarkColumns.baseline().getMaxTemperatureCelsius();

    @DisplayName("주 결과판을 엔진이 보는 온도로 12지점 훑으면 온도 상한 열의 65℃ 를 넘는 노드는 bm-02 · bm-05 뿐이고 기준 정책의 75℃ 를 넘는 지점은 없다")
    @Test
    void main_board_exceeds_only_the_lowered_limit_on_two_nodes() {
        SyntheticCluster main = CLUSTERS.get(BenchmarkClusters.MAIN);
        Map<String, Integer> aboveLowered = new TreeMap<>();
        int abovePolicy = 0;
        for (long t : PlacementBenchmarkService.sweepPoints(main)) {
            for (NodeResource node : PlacementBenchmarkService.nodesAt(main, t)) {
                if (node.getTemperatureCelsius() > LOWERED) aboveLowered.merge(node.getNodeId(), 1, Integer::sum);
                if (node.getTemperatureCelsius() > POLICY) abovePolicy++;
            }
        }
        assertEquals(Map.of("bm-02:9100", 9, "bm-05:9100", 8), aboveLowered);
        assertEquals(0, abovePolicy, "기준 정책 상한을 넘는 지점이 생기면 판 파일이나 정책 기본값이 바뀐 것이다");
    }

    @DisplayName("주 결과판의 대조표는 노드마다 한 행이고, 넘은 지점 수와 생성식 온도의 최솟값 · 최댓값이 12지점을 다시 훑은 값과 같다")
    @Test
    void main_board_rows_match_a_direct_sweep() {
        SyntheticCluster main = CLUSTERS.get(BenchmarkClusters.MAIN);
        List<TemperatureLimitCheck> rows = PlacementBenchmarkService.temperatureLimitCheck(main, LOWERED, POLICY);
        List<Long> sweep = PlacementBenchmarkService.sweepPoints(main);
        assertEquals(12, sweep.size());
        assertEquals(main.nodes().size(), rows.size());

        for (TemperatureLimitCheck row : rows) {
            SyntheticCluster.Node node = main.nodes().stream()
                    .filter(n -> n.nodeId().equals(row.nodeId())).findFirst().orElseThrow();
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            int aboveLowered = 0;
            int abovePolicy = 0;
            for (long t : sweep) {
                double generated = main.temperature(node, t);
                double seen = seenTemperature(main, node.nodeId(), t);
                min = Math.min(min, generated);
                max = Math.max(max, generated);
                if (seen > LOWERED) aboveLowered++;
                if (seen > POLICY) abovePolicy++;
            }
            assertEquals(node.ipmi(), row.measured(), row.nodeId());
            assertEquals(min, row.minTemperatureC(), row.nodeId());
            assertEquals(max, row.maxTemperatureC(), row.nodeId());
            assertEquals(aboveLowered, row.pointsAboveLoweredLimit(), row.nodeId());
            assertEquals(abovePolicy, row.pointsAbovePolicyLimit(), row.nodeId());
        }
        Map<String, Integer> aboveLowered = rows.stream().filter(r -> r.pointsAboveLoweredLimit() > 0)
                .collect(Collectors.toMap(TemperatureLimitCheck::nodeId, TemperatureLimitCheck::pointsAboveLoweredLimit));
        assertEquals(Map.of("bm-02:9100", 9, "bm-05:9100", 8), aboveLowered);
        assertTrue(rows.stream().allMatch(r -> r.pointsAbovePolicyLimit() == 0));
    }

    @DisplayName("온도가 상한과 정확히 같은 지점은 넘은 지점으로 세지 않고, 두 상한을 넘는 노드는 두 지점 수를 따로 세며, 행은 판 파일의 순서가 아니라 nodeId 순이다")
    @Test
    void a_temperature_equal_to_the_limit_does_not_count_and_rows_are_sorted() {
        // 사용률을 흔들지 않는 노드 넷(swing 0) — n1 은 50 + 0.3 × 50 = 65.0, n2 는 66.0, n3 은 45 + 0.3 × 100 = 75.0, n4 는 76.0
        SyntheticCluster cluster = clusterFrom("""
                periodMinutes: 60
                swing: 0
                seed: 7
                nodes:
                  - { nodeId: n3, cores: 8, memoryMb: 32768, cpuUsage: 100, baseTemperature: 45, swing: 0, ipmi: true }
                  - { nodeId: n4, cores: 8, memoryMb: 32768, cpuUsage: 100, baseTemperature: 46, swing: 0, ipmi: true }
                  - { nodeId: n1, cores: 8, memoryMb: 32768, cpuUsage: 50, baseTemperature: 50, swing: 0, ipmi: true }
                  - { nodeId: n2, cores: 8, memoryMb: 32768, cpuUsage: 50, baseTemperature: 51, swing: 0, ipmi: true }
                """);
        List<TemperatureLimitCheck> rows = PlacementBenchmarkService.temperatureLimitCheck(cluster, LOWERED, POLICY);

        assertEquals(List.of("n1", "n2", "n3", "n4"), rows.stream().map(TemperatureLimitCheck::nodeId).toList());
        assertEquals(new TemperatureLimitCheck("n1", true, 65.0, 65.0, 0, 0), rows.get(0));
        assertEquals(new TemperatureLimitCheck("n2", true, 66.0, 66.0, 12, 0), rows.get(1));
        assertEquals(new TemperatureLimitCheck("n3", true, 75.0, 75.0, 12, 0), rows.get(2));
        assertEquals(new TemperatureLimitCheck("n4", true, 76.0, 76.0, 12, 12), rows.get(3));
    }

    @DisplayName("계측 공백판의 미계측 노드는 생성식 최고 온도가 65℃ 를 넘어도 엔진이 0℃ 로 보아 넘은 지점이 0 이고, 최솟값 · 최댓값은 생성식 온도로 잰다")
    @Test
    void unmeasured_nodes_never_exceed_a_limit() {
        SyntheticCluster sparse = CLUSTERS.get(BenchmarkClusters.SPARSE);
        List<TemperatureLimitCheck> unmeasured = PlacementBenchmarkService.temperatureLimitCheck(sparse, LOWERED, POLICY)
                .stream().filter(r -> !r.measured()).toList();

        assertFalse(unmeasured.isEmpty(), "계측 공백판에 미계측 노드가 없으면 이 테스트가 재는 것이 없다");
        assertTrue(unmeasured.stream().anyMatch(r -> r.maxTemperatureC() > LOWERED),
                "생성식으로는 65℃ 를 넘는 미계측 노드가 있어야 이 확인이 뜻을 가진다");
        for (TemperatureLimitCheck row : unmeasured) {
            assertEquals(0, row.pointsAboveLoweredLimit(), row.nodeId());
            assertEquals(0, row.pointsAbovePolicyLimit(), row.nodeId());
            SyntheticCluster.Node node = sparse.nodes().stream()
                    .filter(n -> n.nodeId().equals(row.nodeId())).findFirst().orElseThrow();
            List<Double> generated = PlacementBenchmarkService.sweepPoints(sparse).stream()
                    .map(t -> sparse.temperature(node, t)).toList();
            assertEquals(generated.stream().min(Double::compare).orElseThrow(), row.minTemperatureC(), row.nodeId());
            assertEquals(generated.stream().max(Double::compare).orElseThrow(), row.maxTemperatureC(), row.nodeId());
            assertTrue(row.minTemperatureC() > 0, row.nodeId() + " 의 최솟값이 엔진이 보는 0℃ 로 바뀌었다");
        }
    }

    /**
     * 표가 엔진의 판단과 같은 것을 세는지 — 지점마다 1코어 워크로드를 실제 제약 평가에 넣어 현재 온도 초과로 걸린 횟수를 센다.
     * 사용률 상한이 먼저 거르지 않도록 정책 사본의 CPU · 메모리 상한만 100 으로 올리고, 온도 상한은 표와 같은 두 값을 쓴다.
     */
    @DisplayName("주 결과판 · 계측 공백판 · 반증판에서 1코어 워크로드를 두 상한의 정책으로 평가하면 현재 온도 초과로 걸린 횟수가 대조표의 두 지점 수와 같다")
    @Test
    void the_table_counts_what_the_engine_excludes() {
        ConstraintEvaluator evaluator = BenchmarkContexts.freshEvaluator(new SchedulerProperties());
        Workload probe = new Workload("probe", 1, 1024);
        for (String boardId : List.of(BenchmarkClusters.MAIN, BenchmarkClusters.SPARSE, BenchmarkClusters.ADVERSARIAL)) {
            SyntheticCluster cluster = CLUSTERS.get(boardId);
            Map<String, Integer> lowered = new TreeMap<>();
            Map<String, Integer> policy = new TreeMap<>();
            for (long t : PlacementBenchmarkService.sweepPoints(cluster)) {
                List<NodeResource> nodes = PlacementBenchmarkService.nodesAt(cluster, t);
                countExcluded(evaluator, probe, nodes, policyWithLimit(LOWERED), lowered);
                countExcluded(evaluator, probe, nodes, policyWithLimit(POLICY), policy);
            }
            for (TemperatureLimitCheck row : PlacementBenchmarkService.temperatureLimitCheck(cluster, LOWERED, POLICY)) {
                assertEquals(lowered.getOrDefault(row.nodeId(), 0), row.pointsAboveLoweredLimit(), boardId + " " + row.nodeId());
                assertEquals(policy.getOrDefault(row.nodeId(), 0), row.pointsAbovePolicyLimit(), boardId + " " + row.nodeId());
            }
        }
    }

    @DisplayName("위상 독립 판은 Board 마다 자기 seed 의 클러스터로 센 대조표를 싣는다")
    @Test
    void independent_boards_carry_the_table_of_their_own_seed() {
        PlacementBenchmarkResponse response = newService().compareFresh(BenchmarkClusters.MAIN_INDEPENDENT, "heavytail");
        SyntheticCluster cluster = CLUSTERS.get(BenchmarkClusters.MAIN_INDEPENDENT);

        assertEquals(cluster.seeds().size(), response.boards().size());
        Set<List<TemperatureLimitCheck>> distinct = new HashSet<>();
        for (Board board : response.boards()) {
            assertEquals(PlacementBenchmarkService.temperatureLimitCheck(cluster.withSeed(board.phaseSeed()), LOWERED, POLICY),
                    board.temperatureLimitCheck(), "seed " + board.phaseSeed());
            distinct.add(board.temperatureLimitCheck());
        }
        assertTrue(distinct.size() > 1, "seed 마다 표가 같으면 다른 seed 의 표를 실어도 이 테스트가 알아채지 못한다");
    }

    // ── 도움 메서드 ───────────────────────────────────────────

    private static double seenTemperature(SyntheticCluster cluster, String nodeId, long t) {
        return PlacementBenchmarkService.nodesAt(cluster, t).stream()
                .filter(n -> n.getNodeId().equals(nodeId)).findFirst().orElseThrow()
                .getTemperatureCelsius();
    }

    private static SchedulingPolicy policyWithLimit(double limit) {
        SchedulingPolicy policy = BenchmarkColumns.baseline().detachedCopy();
        policy.setMaxCpuUsagePercent(100);
        policy.setMaxMemoryUsagePercent(100);
        policy.setMaxTemperatureCelsius(limit);
        return policy;
    }

    private static void countExcluded(ConstraintEvaluator evaluator, Workload probe, List<NodeResource> nodes,
                                      SchedulingPolicy policy, Map<String, Integer> counts) {
        var ctx = BenchmarkContexts.copyOf(nodes, policy, evaluator);
        for (NodeResource node : ctx.nodes()) {
            Exclusion exclusion = evaluator.exclusion(probe, node, ctx);
            if (exclusion != null && exclusion.reason() == ExclusionReason.TEMPERATURE_EXCEEDED) {
                counts.merge(node.getNodeId(), 1, Integer::sum);
            }
        }
    }

    /** 판 파일 한 장을 임시 파일로 써서 읽는다 — 파서를 그대로 거쳐야 판의 기본값도 운영과 같다. */
    private static SyntheticCluster clusterFrom(String yaml) {
        try {
            Path file = Files.createTempFile("temperature-limit-board", ".yml");
            file.toFile().deleteOnExit();
            Files.writeString(file, yaml);
            return SyntheticCluster.load(file.toUri().toString());
        } catch (IOException e) {
            throw new IllegalStateException("판 임시 파일을 만들 수 없습니다", e);
        }
    }

    private static PlacementBenchmarkService newService() {
        SchedulerProperties props = new SchedulerProperties();
        return new PlacementBenchmarkService(
                new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(),
                        new TemperatureAwareScheduler())),
                props, new BenchmarkClusters(),
                new PowerModel(props), new ThermalModel(props));
    }
}

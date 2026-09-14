package com.innogrid.aims.scheduler.metrics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 위상 방식 — legacy 식의 값을 리터럴로 고정하고, independent 가 seed 마다 <b>노드별로</b> 위상을 움직이는지 본다.
 *
 * <p>"상수 가산이 아님" 만으로는 독립성의 증거가 못 된다(단사 사상이면 무엇이든 통과한다). 그래서 ⓐ seed 마다 노드 위상의 순위
 * 순열이 달라지고 ⓑ 모든 노드쌍의 위상차가 seed 에 따라 변하며 ⓒ 여러 seed 의 위상이 대략 고르게 흩어지는지를 본다.</p>
 */
class SyntheticClusterPhaseModeTest {

    /** 주 결과판(legacy, seed 7) 노드별 위상 — 1000분위 정수. 이 값에 6판의 다이제스트 기준선이 묶여 있다. */
    private static final Map<String, Integer> LEGACY_MAIN_PHASE_MILLI = Map.ofEntries(
            Map.entry("bm-01:9100", 446),
            Map.entry("bm-02:9100", 597),
            Map.entry("bm-03:9100", 748),
            Map.entry("bm-04:9100", 899),
            Map.entry("bm-05:9100", 50),
            Map.entry("bm-06:9100", 201),
            Map.entry("bm-07:9100", 352),
            Map.entry("bm-08:9100", 503),
            Map.entry("bm-09:9100", 654),
            Map.entry("bm-10:9100", 976),
            Map.entry("bm-11:9100", 127),
            Map.entry("bm-12:9100", 278),
            Map.entry("bm-13:9100", 429),
            Map.entry("bm-14:9100", 580));

    private static int milli(double phase) {
        return (int) Math.round(phase / (2 * Math.PI) * 1000) % 1000;
    }

    @DisplayName("legacy 위상은 옛 식 그대로다 — 주 결과판 노드별 값 리터럴")
    @Test
    void legacy_phase_values_are_pinned() {
        SyntheticCluster main = new BenchmarkClusters().get(BenchmarkClusters.MAIN);
        assertEquals(SyntheticCluster.PhaseMode.LEGACY, main.phaseMode());
        assertEquals(List.of(7L), main.seeds());
        Map<String, Integer> actual = main.nodes().stream()
                .collect(Collectors.toMap(SyntheticCluster.Node::nodeId, n -> milli(main.phaseOf(n.nodeId()))));
        assertEquals(LEGACY_MAIN_PHASE_MILLI, actual, "legacy 위상이 바뀌었다 — 6판 기준선 무효");
        // legacy 결함의 재현: seed 를 바꾸면 전 노드가 같은 상수만큼 회전한다(nodeId 길이 전부 10 → 31^10 mod 1000 = 801)
        SyntheticCluster shifted = main.withSeed(11);
        Set<Integer> deltas = main.nodes().stream()
                .map(n -> Math.floorMod(milli(shifted.phaseOf(n.nodeId())) - milli(main.phaseOf(n.nodeId())), 1000))
                .collect(Collectors.toSet());
        assertEquals(1, deltas.size(), "legacy 에서 seed 는 전 노드 위상에 같은 상수만 더한다: " + deltas);
        assertEquals(Math.floorMod(4 * 801, 1000), deltas.iterator().next(), "상수 = Δseed × 31^10 mod 1000");
    }

    @DisplayName("independent 위상은 seed 마다 노드별로 독립으로 움직인다 — 순위 순열·쌍 위상차·균일성")
    @Test
    void independent_phase_moves_nodes_independently() {
        SyntheticCluster board = new BenchmarkClusters().get(BenchmarkClusters.MAIN_INDEPENDENT);
        assertEquals(SyntheticCluster.PhaseMode.INDEPENDENT, board.phaseMode());
        assertEquals(List.of(7L, 11L, 13L), board.seeds());
        assertEquals(BenchmarkRules.PHASE_SEED_COUNT, board.seeds().size());

        List<String> ids = board.nodes().stream().map(SyntheticCluster.Node::nodeId).toList();
        List<List<Integer>> rankings = new ArrayList<>();
        Set<Set<Integer>> pairDiffs = new HashSet<>();
        List<Integer> all = new ArrayList<>();
        for (long seed : board.seeds()) {
            SyntheticCluster s = board.withSeed(seed);
            List<Integer> phases = ids.stream().map(id -> milli(s.phaseOf(id))).toList();
            all.addAll(phases);
            // ⓐ 순위 순열
            rankings.add(IntStream.range(0, ids.size()).boxed()
                    .sorted((a, b) -> Integer.compare(phases.get(a), phases.get(b))).toList());
            // ⓑ 모든 노드쌍 위상차 집합
            Set<Integer> diffs = new HashSet<>();
            for (int i = 0; i < ids.size(); i++) for (int j = i + 1; j < ids.size(); j++) diffs.add(Math.floorMod(phases.get(i) - phases.get(j), 1000));
            pairDiffs.add(diffs);
        }
        assertEquals(board.seeds().size(), new HashSet<>(rankings).size(), "seed 마다 노드 위상의 순위 순열이 달라야 한다 — 상수 회전이면 같다");
        assertEquals(board.seeds().size(), pairDiffs.size(), "노드쌍 위상차 집합이 seed 마다 달라야 한다 — 상수 회전이면 같다");
        // ⓒ 대략적 균일성 — 42개 값이 4분위에 전부 들어 있고 어느 분위도 절반을 넘지 않는다
        int[] quartile = new int[4];
        for (int p : all) quartile[p * 4 / 1000]++;
        for (int q = 0; q < 4; q++) {
            assertTrue(quartile[q] > 0 && quartile[q] < all.size() / 2, "위상이 한 분위에 몰렸다: " + java.util.Arrays.toString(quartile));
        }
    }

    @DisplayName("독립 판의 노드 블록은 짝 판과 같고 위상 방식·seed 만 다르다")
    @Test
    void independent_boards_share_their_partner_node_blocks() {
        BenchmarkClusters clusters = new BenchmarkClusters();
        assertEquals(clusters.get(BenchmarkClusters.MAIN).nodes(), clusters.get(BenchmarkClusters.MAIN_INDEPENDENT).nodes());
        assertEquals(clusters.get(BenchmarkClusters.CONVEX_MISRATED).nodes(), clusters.get(BenchmarkClusters.CONVEX_MISRATED_INDEPENDENT).nodes());
        assertEquals(clusters.get(BenchmarkClusters.CONVEX_MISRATED).powerExponent(), clusters.get(BenchmarkClusters.CONVEX_MISRATED_INDEPENDENT).powerExponent());
        for (String id : clusters.boardIds()) {
            SyntheticCluster c = clusters.get(id);
            boolean independent = BenchmarkRules.PHASE_INDEPENDENT_BOARDS.contains(id);
            assertEquals(independent ? SyntheticCluster.PhaseMode.INDEPENDENT : SyntheticCluster.PhaseMode.LEGACY, c.phaseMode(), id);
            assertEquals(independent ? BenchmarkRules.PHASE_SEED_COUNT : 1, c.seeds().size(), id + " — seed 수");
        }
    }

    @DisplayName("seed 와 seeds 를 함께 적거나 중복·빈 목록·모르는 phaseMode 는 거부한다")
    @Test
    void rejects_malformed_phase_declarations() {
        String nodes = "name: t\nnodes:\n  - { mdcId: a, nodeId: \"n-a:9100\", cores: 8, memoryMb: 32768, idleWatts: 100, capacityWatts: 400 }\n";
        for (String bad : List.of("seed: 7\nseeds: [7, 11]\n", "seeds: [7, 7]\n", "seeds: []\n", "phaseMode: random\n", "seeds: [7, 1.5]\n", "seeds: [7, x]\n", "seed: 7.9\n", "seeds: [99999999999999999999]\n")) {
            Object root = new Yaml().load(nodes + bad);
            assertThrows(IllegalArgumentException.class, () -> SyntheticCluster.parse(root, "test"), bad);
        }
        SyntheticCluster c = SyntheticCluster.parse(new Yaml().load(nodes + "seeds: [11, 13]\nphaseMode: Independent\n"), "test");
        assertEquals(11L, c.seed(), "seeds 의 첫 항목이 seed 다");
        assertEquals(SyntheticCluster.PhaseMode.INDEPENDENT, c.phaseMode());
        assertEquals(c.nodes(), c.withSeed(99).nodes(), "withSeed 는 노드를 그대로 든다");
        assertEquals(List.of(99L), c.withSeed(99).seeds(), "사본의 seeds 는 자기 seed 하나 — 불변식 유지");
    }
}

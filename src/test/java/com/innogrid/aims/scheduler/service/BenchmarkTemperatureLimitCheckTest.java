package com.innogrid.aims.scheduler.service;

import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

package com.innogrid.aims.scheduler.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.service.MetricsQueryGuard;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 가상 클러스터의 <b>시각 불변 성질</b> — 한 주기(60분)를 30초 간격으로 전부 훑어, 언제 실행해도 성립하는 것만 가려낸다.
 *
 * <p>합성 값은 시각의 함수라 같은 시각이면 재현되지만 <b>실행 시각이 다르면 값이 달라진다.</b> 그래서 시나리오 테스트
 * ({@code SyntheticModeScenarioTest})는 실제 시계로 도는 순간 한 컷만 본다. 여기서 확인된 성질만 그쪽 단언으로 쓴다.</p>
 *
 * <p>실제 수집 경로({@link NodeResourceCollector} + {@link SyntheticMetricsGateway})를 그대로 쓰고 시각만 고정한다.
 * 스프링 컨텍스트는 쓰지 않는다.</p>
 */
class SyntheticModeInvariantTest {

    private static final long PERIOD_SECONDS = 3600;   // synthetic-cluster.yml 의 periodMinutes: 60
    private static final long STEP_SECONDS = 30;       // 120 표본
    private static final long BASE_EPOCH = 1_800_000_000L;

    private static final String IDLE_HIGH_POWER = "busan-02:9100";   // 유휴(CPU<5%)인데 유휴 전력 380W

    private final SyntheticCluster cluster = SyntheticCluster.load("classpath:scheduler/synthetic-cluster.yml");
    private final SchedulerProperties properties = new SchedulerProperties();
    private final PowerModel powerModel = new PowerModel(properties);

    /** 고정 시각으로 실제 수집을 한 번 돌린다. */
    private NodeResourceCollector.Snapshot collectAt(long epochSecond) {
        SyntheticMetricsGateway gateway = new SyntheticMetricsGateway(cluster, () -> Instant.ofEpochSecond(epochSecond));
        return new NodeResourceCollector(gateway, properties, powerModel, new MetricsQueryGuard(2)).collect();
    }

    private static NodeResource node(NodeResourceCollector.Snapshot s, String nodeId) {
        return s.nodes().stream().filter(n -> n.getNodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    @DisplayName("유휴인데 전력을 많이 쓰는 노드는 모든 시각에 가동 중인 계측 노드 전부보다 PEI 가 낮다")
    @Test
    void idle_high_power_node_least_efficient_at_every_moment() {
        double worstMargin = Double.MAX_VALUE;
        for (long t = BASE_EPOCH; t < BASE_EPOCH + PERIOD_SECONDS; t += STEP_SECONDS) {
            final long at = t;
            NodeResourceCollector.Snapshot snapshot = collectAt(at);
            NodeResource idle = node(snapshot, IDLE_HIGH_POWER);
            assertFalse(powerModel.isActive(idle), "유휴 노드는 언제나 가동 판정 아래여야 한다: t=" + at + " cpu=" + idle.getCpuUsagePercent());

            List<NodeResource> activeMeasured = snapshot.nodes().stream()
                    .filter(n -> n.getPowerWatts() > 0 && powerModel.isActive(n))
                    .toList();
            assertFalse(activeMeasured.isEmpty(), "가동 중인 계측 노드가 있어야 비교가 성립한다: t=" + at);

            double idlePei = powerModel.rawPei(idle);
            for (NodeResource active : activeMeasured) {
                double margin = powerModel.rawPei(active) - idlePei;
                assertTrue(margin > 0, () -> String.format(
                        "t=%d 에서 %s(PEI %.5f)가 유휴 고전력 노드(PEI %.5f)보다 효율이 낮다",
                        at, active.getNodeId(), powerModel.rawPei(active), idlePei));
                worstMargin = Math.min(worstMargin, margin);
            }
        }
        assertTrue(worstMargin > 0.01, "여유가 충분해야 흔들리지 않는다. 최소 여유 " + worstMargin);
    }

    @DisplayName("수집 결과의 형태는 모든 시각에 같다 — 후보 8대·제외 0·미계측 3·GPU 4와 2")
    @Test
    void collection_shape_stable_at_every_moment() {
        for (long t = BASE_EPOCH; t < BASE_EPOCH + PERIOD_SECONDS; t += STEP_SECONDS) {
            NodeResourceCollector.Snapshot s = collectAt(t);
            assertEquals(8, s.nodes().size(), "t=" + t);
            assertTrue(s.excluded().isEmpty(), "t=" + t);
            assertEquals(3, s.nodes().stream().filter(n -> n.getPowerSource() == NodeResource.PowerSource.NONE).count(), "t=" + t);
            assertEquals(Set.of(4, 2), s.nodes().stream().filter(n -> n.getTotalGpu() > 0)
                    .map(NodeResource::getTotalGpu).collect(Collectors.toSet()), "t=" + t);
            assertEquals(3, s.nodes().stream().map(NodeResource::getMdcId).distinct().count(), "t=" + t);
        }
    }

    @DisplayName("최저 PEI 노드는 시각에 따라 바뀐다 — 옛 단언(항상 busan-02)이 흔들린 이유를 고정한다")
    @Test
    void lowest_pei_node_changes_over_time() {
        TreeSet<String> lowest = new TreeSet<>();
        for (long t = BASE_EPOCH; t < BASE_EPOCH + PERIOD_SECONDS; t += STEP_SECONDS) {
            lowest.add(collectAt(t).nodes().stream()
                    .filter(n -> n.getPowerWatts() > 0)
                    .min((a, b) -> Double.compare(powerModel.rawPei(a), powerModel.rawPei(b)))
                    .orElseThrow().getNodeId());
        }
        assertTrue(lowest.size() > 1, "최저 PEI 노드가 하나뿐이면 옛 단언을 되살려도 되지만, 실제로는 여럿이다: " + lowest);
        assertTrue(lowest.contains(IDLE_HIGH_POWER), "유휴 고전력 노드는 그중 하나여야 한다: " + lowest);
        // CPU 가 0 에 가까워지는 순간의 노드는 PEI 도 0 에 가까워 최저가 된다 — "일을 안 하는 것"이지 "비효율"이 아니다.
        assertTrue(lowest.stream().anyMatch(id -> !id.equals(IDLE_HIGH_POWER)), "다른 노드도 최저가 되는 순간이 있다: " + lowest);
    }
}

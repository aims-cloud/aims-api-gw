package com.innogrid.aims.scheduler.engine;

import java.time.Instant;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;
import com.innogrid.aims.scheduler.metrics.SyntheticMetricsGateway;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;
import com.innogrid.aims.scheduler.service.MetricsQueryGuard;
import com.innogrid.aims.scheduler.service.NodeResourceCollector;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TemperatureAwareScheduler}의 미계측 가드·평균 수정을 <b>실제 가상 클러스터 8대</b>(계측 5·미계측 3)로,
 * <b>한 주기(60분) 전체</b>를 훑어 확인한다.
 *
 * <p>{@code TemperatureAwareUnmeasuredTest}는 손으로 만든 2노드 픽스처로 결함의 정확한 수치(0.5831 대 0.5500 등)를
 * 고정한다. 그 픽스처는 결함의 존재는 증명하지만 <b>실제 수집 경로</b>({@link NodeResourceCollector} +
 * {@link SyntheticMetricsGateway})를 거치지 않는다 — {@code NodeResource}를 직접 만들어 넘기기 때문이다. 이 클래스는
 * 실제 수집 경로를 그대로 타서, 합성 클러스터가 시각에 따라 CPU·온도를 사인 곡선으로 흔드는 동안에도 미계측 가드와
 * 평균 수정이 매 순간 성립하는지를 본다.</p>
 *
 * <p>배포 VM은 노드가 <b>1대</b>뿐이라({@code TemperatureAwareUnmeasuredTest#lone_unmeasured_node_total_score_pinned}
 * 참고) 계측·미계측이 섞인 클러스터에서의 평균 계산이나, 계측 노드끼리의 온도 기반 선택 자체를 볼 수 없다. 이
 * 성질을 실제로 보는 자리는 8대짜리 가상 클러스터인 이 테스트뿐이다.</p>
 */
class TemperatureAwareSyntheticSweepTest {

    private static final long PERIOD_SECONDS = 3600;   // synthetic-cluster.yml의 periodMinutes: 60
    private static final long STEP_SECONDS = 30;       // 120 표본
    private static final long BASE_EPOCH = 1_800_000_000L;

    private static final Set<String> MEASURED = Set.of(
            "seoul-01:9100", "seoul-02:9100", "seoul-03:9100", "busan-01:9100", "busan-02:9100");
    private static final Set<String> UNMEASURED = Set.of(
            "busan-03:9100", "daejeon-01:9100", "daejeon-02:9100");

    private final SyntheticCluster cluster = SyntheticCluster.load("classpath:scheduler/synthetic-cluster.yml");
    private final SchedulerProperties properties = new SchedulerProperties();
    private final PowerModel powerModel = new PowerModel(properties);
    private final ThermalModel thermalModel = new ThermalModel(properties);
    private final TemperatureAwareScheduler scheduler = new TemperatureAwareScheduler();

    /** 고정 시각으로 실제 수집을 한 번 돌린다({@code SyntheticModeInvariantTest}와 같은 조립). */
    private NodeResourceCollector.Snapshot collectAt(long epochSecond) {
        SyntheticMetricsGateway gateway = new SyntheticMetricsGateway(cluster, () -> Instant.ofEpochSecond(epochSecond));
        return new NodeResourceCollector(gateway, properties, powerModel, new MetricsQueryGuard(2)).collect();
    }

    private static SchedulingPolicy policy() {
        return new SchedulingPolicy("test", "test");
    }

    /** 디스크 제약을 유발하지 않는 1코어·512MB·GPU 0·디스크 0 워크로드. */
    private static Workload workload() {
        return new Workload("sweep", 1, 512);
    }

    private static NodeScore score(SchedulingResult result, String nodeId) {
        return result.candidateScores().stream()
                .filter(s -> s.nodeId().equals(nodeId)).findFirst()
                .orElseThrow(() -> new AssertionError(nodeId + " 가 후보에 없다 — synthetic-cluster.yml 이 바뀌었는가"));
    }

    /** 스냅샷이 기대한 계측/미계측 구성인지 먼저 확인한다 — yml이 바뀌면 여기서 정확히 실패한다. */
    private void assertClusterShape(NodeResourceCollector.Snapshot snapshot, long t) {
        Set<String> measured = snapshot.nodes().stream()
                .filter(n -> n.getTemperatureCelsius() > 0).map(NodeResource::getNodeId).collect(Collectors.toSet());
        Set<String> unmeasured = snapshot.nodes().stream()
                .filter(n -> n.getTemperatureCelsius() <= 0).map(NodeResource::getNodeId).collect(Collectors.toSet());
        assertEquals(MEASURED, measured, "t=" + t + " 계측 노드 구성이 바뀌었다 — synthetic-cluster.yml 을 확인하라");
        assertEquals(UNMEASURED, unmeasured, "t=" + t + " 미계측 노드 구성이 바뀌었다 — synthetic-cluster.yml 을 확인하라");
    }

    @DisplayName("미계측 노드는 모든 시각에 열 점수 셋이 중립 0_5다")
    @Test
    void unmeasured_nodes_stay_neutral_at_every_moment() {
        for (long t = BASE_EPOCH; t < BASE_EPOCH + PERIOD_SECONDS; t += STEP_SECONDS) {
            NodeResourceCollector.Snapshot snapshot = collectAt(t);
            assertClusterShape(snapshot, t);
            PlacementContext context = PlacementContext.of(snapshot.nodes(), policy());
            SchedulingResult result = scheduler.schedule(workload(), context);

            for (String nodeId : UNMEASURED) {
                NodeScore s = score(result, nodeId);
                Double headroom = s.details().get("열적여유");
                Double balance = s.details().get("열적균형");
                Double cooling = s.details().get("냉각점수");
                Double measured = s.details().get("온도계측");

                assertFalse(Double.isNaN(headroom), "t=" + t + " node=" + nodeId + " 열적여유가 NaN");
                assertFalse(Double.isNaN(balance), "t=" + t + " node=" + nodeId + " 열적균형이 NaN");
                assertFalse(Double.isNaN(cooling), "t=" + t + " node=" + nodeId + " 냉각점수가 NaN");

                assertEquals(0.5, headroom, 1e-9, "t=" + t + " node=" + nodeId + " 열적여유는 중립이어야 한다");
                assertEquals(0.5, balance, 1e-9, "t=" + t + " node=" + nodeId + " 열적균형은 중립이어야 한다");
                assertEquals(0.5, cooling, 1e-9, "t=" + t + " node=" + nodeId + " 냉각점수는 중립이어야 한다");
                assertEquals(0.0, measured, 1e-9, "t=" + t + " node=" + nodeId + " 온도계측은 0이어야 한다");
            }
        }
    }

    @DisplayName("계측 노드는 모든 시각에 계측으로 표시된다")
    @Test
    void measured_nodes_flagged_at_every_moment() {
        for (long t = BASE_EPOCH; t < BASE_EPOCH + PERIOD_SECONDS; t += STEP_SECONDS) {
            NodeResourceCollector.Snapshot snapshot = collectAt(t);
            assertClusterShape(snapshot, t);
            PlacementContext context = PlacementContext.of(snapshot.nodes(), policy());
            SchedulingResult result = scheduler.schedule(workload(), context);

            for (String nodeId : MEASURED) {
                Double measured = score(result, nodeId).details().get("온도계측");
                assertFalse(Double.isNaN(measured), "t=" + t + " node=" + nodeId + " 온도계측이 NaN");
                assertEquals(1.0, measured, 1e-9, "t=" + t + " node=" + nodeId + " 온도계측은 1이어야 한다");
            }
        }
    }

    @DisplayName("클러스터 평균 온도는 모든 시각에 계측 노드만으로 나온다")
    @Test
    void cluster_average_counts_measured_only_at_every_moment() {
        for (long t = BASE_EPOCH; t < BASE_EPOCH + PERIOD_SECONDS; t += STEP_SECONDS) {
            NodeResourceCollector.Snapshot snapshot = collectAt(t);
            assertClusterShape(snapshot, t);
            PlacementContext context = PlacementContext.of(snapshot.nodes(), policy());
            SchedulingResult result = scheduler.schedule(workload(), context);

            double expectedMeasuredOnly = snapshot.nodes().stream()
                    .filter(n -> n.getTemperatureCelsius() > 0)
                    .mapToDouble(thermalModel::effectiveTemperature)
                    .average().orElse(0);

            double expectedAllEight = snapshot.nodes().stream()
                    .mapToDouble(thermalModel::effectiveTemperature)
                    .average().orElse(0);

            // 클러스터 평균은 노드별 값이 아니라 <b>실행 단위 값</b>이다 — 후보 여덟 대가 모두 같은 수를
            // 담고 있어야 한다. 대표로 첫 노드 한 대만 읽으면 이 성질을 확인하지 못한다: 채움 조건을
            // "클러스터에 계측 노드가 있는가"에서 "이 노드가 계측인가"로 바꾸는 변이는 미계측 3대의
            // 칸만 null로 만드는데, 첫 노드가 계측이면 그 변이가 그대로 통과한다.
            assertEquals(snapshot.nodes().size(), result.candidateScores().size(),
                    "t=" + t + " 후보 수가 스냅샷과 달라 순회가 헐거워졌다");

            for (NodeScore s : result.candidateScores()) {
                Double actual = s.details().get("클러스터평균온도C");

                // 계측 노드가 0대일 때만 null이어야 한다 — 이 클러스터는 계측 5대라 미계측 노드의
                // 칸에도 값이 있어야 한다. 언박싱 전에 확인해 NPE 대신 의미 있는 실패를 남긴다.
                assertNotNull(actual, "t=" + t + " node=" + s.nodeId() + " 클러스터평균온도C 가 null이다 — "
                        + "계측 노드가 0대일 때만 null 이어야 하는데, 이 클러스터는 계측 5대이므로 "
                        + "미계측 노드의 칸에도 값이 있어야 한다");
                assertFalse(Double.isNaN(actual), "t=" + t + " node=" + s.nodeId() + " 클러스터평균온도C 가 NaN");
                assertEquals(expectedMeasuredOnly, actual, 1e-4,
                        "t=" + t + " node=" + s.nodeId() + " 엔진의 클러스터평균온도C(" + actual
                                + ")가 계측 노드만의 평균(" + expectedMeasuredOnly + ")과 같아야 한다");
            }

            double diff = Math.abs(expectedMeasuredOnly - expectedAllEight);
            assertTrue(diff >= 10.0,
                    "t=" + t + " 계측만의 평균(" + expectedMeasuredOnly + ")과 8대 전체 평균(" + expectedAllEight
                            + ")의 차가 10도 이상이어야 미계측을 평균에 넣었을 때와 뚜렷이 구분된다. 차=" + diff);
        }
    }

    @DisplayName("모든 시각에 계측된 노드가 선택된다")
    @Test
    void selected_node_is_measured_at_every_moment() {
        double minMargin = Double.MAX_VALUE;
        String worstMessage = "표본이 없음";
        int skippedNoEligibleUnmeasured = 0;

        for (long t = BASE_EPOCH; t < BASE_EPOCH + PERIOD_SECONDS; t += STEP_SECONDS) {
            NodeResourceCollector.Snapshot snapshot = collectAt(t);
            assertClusterShape(snapshot, t);
            PlacementContext context = PlacementContext.of(snapshot.nodes(), policy());
            SchedulingResult result = scheduler.schedule(workload(), context);

            assertTrue(result.success(), "t=" + t + " 배치가 성공해야 한다");
            assertNotNull(result.selectedNodeId(), "t=" + t + " 선택 노드가 있어야 한다");
            assertTrue(MEASURED.contains(result.selectedNodeId()),
                    "t=" + t + " 선택 노드(" + result.selectedNodeId() + ")는 계측 5대 중 하나여야 한다");

            double selectedTotal = score(result, result.selectedNodeId()).totalScore();
            assertFalse(Double.isNaN(selectedTotal), "t=" + t + " 선택 노드 총점이 NaN");

            // score() 는 eligible 을 보지 않는다 — 부적격(제외된) 노드까지 포함하면 "선택될 수 없는 노드와의
            // 마진" 을 재게 된다. 적격 미계측 노드로 한정한다(현 픽스처는 전원 적격이라 지금은 무해하다).
            OptionalDouble bestUnmeasuredTotalOpt = UNMEASURED.stream()
                    .map(nodeId -> score(result, nodeId))
                    .filter(NodeScore::eligible)
                    .mapToDouble(NodeScore::totalScore)
                    .max();

            if (bestUnmeasuredTotalOpt.isEmpty()) {
                // 적격 미계측 노드가 하나도 없는 시각 — 비교 대상이 없으므로 마진 비교를 건너뛴다.
                skippedNoEligibleUnmeasured++;
                continue;
            }
            double bestUnmeasuredTotal = bestUnmeasuredTotalOpt.getAsDouble();
            assertFalse(Double.isNaN(bestUnmeasuredTotal), "t=" + t + " 최고 미계측 노드 총점이 NaN");

            double margin = selectedTotal - bestUnmeasuredTotal;
            String message = "t=" + t + " 선택 노드=" + result.selectedNodeId() + "(총점 " + selectedTotal
                    + ") vs 최고 미계측 노드 총점 " + bestUnmeasuredTotal + " — 마진=" + margin;
            assertTrue(margin > 0, message);

            if (margin < minMargin) {
                minMargin = margin;
                worstMessage = message;
            }
        }

        // 0.02는 프로덕션의 성질이 아니라 이 픽스처 스냅샷의 최소 마진이다 — 실패 시 회귀인지
        // synthetic-cluster.yml 드리프트로 마진 자체가 바뀐 것인지는 최소 마진 표본 값을 보고 판단하라.
        assertTrue(minMargin > 0.02, "여유가 충분해야 흔들리지 않는다(0.02 는 프로덕션 성질이 아니라 이 픽스처 스냅샷의 값이다). "
                + "적격 미계측 노드가 없어 건너뛴 시각 수=" + skippedNoEligibleUnmeasured + ". 최소 마진 표본: " + worstMessage);
    }
}

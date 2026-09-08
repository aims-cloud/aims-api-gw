package com.innogrid.aims.scheduler.benchmark;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.benchmark.GroundTruthOracle.IdleGap;
import com.innogrid.aims.scheduler.benchmark.GroundTruthOracle.PowerControl;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 생성식 오라클이 <b>클러스터 파일의 생성식 그대로</b> 계산하는지 고정한다.
 *
 * <p>합성 클러스터 파일을 그대로 읽어 쓴다 — 손으로 계산할 수 있는 값들이라 기대값을 식과 함께 적어 둔다.
 * 여기가 빨간불이면 벤치마크의 주 지표가 다른 물리를 재고 있다는 뜻이다.</p>
 *
 * <p>다섯째·여섯째·일곱째 테스트는 <b>순환 논증이 아님을 보이는 증거</b>다. 엔진의 유휴 전력 가정
 * (정격 × 0.3)과 파일에 적힌 실제 유휴 전력이 노드마다 얼마나 다른지 고정해 둔다.</p>
 */
class GroundTruthOracleTest {

    private static final double EPS = 1e-9;

    /** 엔진 기본 유휴 비율 — {@code SchedulerProperties.Power.idleRatio} 의 기본값. */
    private static final double ENGINE_IDLE_RATIO = 0.3;

    private final GroundTruthOracle oracle =
            new GroundTruthOracle(SyntheticCluster.load("classpath:scheduler/synthetic-cluster.yml"));

    @DisplayName("노드 전력은 생성식과 같다")
    @Test
    void node_power_follows_the_generator_formula() {
        assertEquals(382.4, oracle.nodePowerWatts("busan-02:9100", 2), EPS,
                "busan-02 는 유휴 380W·정격 500W — 380 + (500−380) × 2/100 = 382.4W");
        assertEquals(314.0, oracle.nodePowerWatts("seoul-01:9100", 35), EPS,
                "seoul-01 은 유휴 160W·정격 600W — 160 + (600−160) × 35/100 = 314.0W");

        assertEquals(380.0, oracle.nodePowerWatts("busan-02:9100", 0), EPS,
                "CPU 0% 면 유휴 전력 그대로 380W — 전원을 끄지 않는 한 이 전력은 계속 든다");
        assertEquals(500.0, oracle.nodePowerWatts("busan-02:9100", 100), EPS,
                "CPU 100% 면 정격 500W — 생성식의 위쪽 끝");
    }

    @DisplayName("전원 제어가 없으면 유휴 노드도 전력을 먹는다")
    @Test
    void cluster_power_without_control_charges_idle_nodes() {
        Map<String, Double> allIdle = new LinkedHashMap<>();
        for (String nodeId : oracle.nodeIds()) allIdle.put(nodeId, 0.0);
        Set<String> used = Set.of("busan-02:9100");

        assertEquals(1440.0, oracle.clusterPowerWatts(allIdle, used, PowerControl.UNAVAILABLE), EPS,
                "전 노드 통전 — Σ idleWatts = 160+220+150+140+380+150+120+120 = 1440W");
        assertEquals(380.0, oracle.clusterPowerWatts(allIdle, used, PowerControl.AVAILABLE), EPS,
                "사용 노드 1대만 통전 — busan-02 의 idleWatts 380W 뿐");

        assertEquals(1060.0,
                oracle.clusterPowerWatts(allIdle, used, PowerControl.UNAVAILABLE)
                        - oracle.clusterPowerWatts(allIdle, used, PowerControl.AVAILABLE), EPS,
                "두 판의 차 1440 − 380 = 1060W 가 전원 제어로 아낄 수 있다고 가정하는 양이다 — "
                        + "현 시스템에는 노드 전원을 끄는 수단이 없으므로 실제로는 아낄 수 없다");
    }

    @DisplayName("전원 제어가 없으면 배치 증분은 동적분뿐이다")
    @Test
    void marginal_power_without_control_is_dynamic_only() {
        assertEquals(10.0, oracle.marginalPowerWatts("busan-02:9100", 2, true, PowerControl.UNAVAILABLE), EPS,
                "busan-02 는 동적 범위 (500−380)=120W·24코어 — 2코어면 120 × 2/24 = 10.0W. "
                        + "빈 노드여도 이미 통전되어 있으므로 유휴 전력이 새로 들지 않는다");
        assertEquals(oracle.marginalPowerWatts("busan-02:9100", 2, true, PowerControl.UNAVAILABLE),
                oracle.marginalPowerWatts("busan-02:9100", 2, false, PowerControl.UNAVAILABLE), EPS,
                "전원 제어가 없으면 빈 노드였는지가 증분을 바꾸지 않는다 — 깨울 것이 없다");
    }

    @DisplayName("전원 제어가 있으면 빈 노드의 실제 유휴가 더해진다")
    @Test
    void marginal_power_with_control_adds_the_real_idle() {
        assertEquals(390.0, oracle.marginalPowerWatts("busan-02:9100", 2, true, PowerControl.AVAILABLE), EPS,
                "동적분 10.0W + 실제 유휴 380W = 390.0W — 엔진이라면 이 자리에 정격×0.3 = 150W 를 넣어 "
                        + "160W 로 계산한다(모델 오사양 230W)");
        assertEquals(10.0, oracle.marginalPowerWatts("busan-02:9100", 2, false, PowerControl.AVAILABLE), EPS,
                "이미 도는 노드면 깨우는 비용이 없어 동적분 10.0W 뿐");
    }

    @DisplayName("엔진 가정과 실제 유휴의 격차가 가장 큰 노드는 우리가 심은 노드다")
    @Test
    void engine_assumption_differs_most_on_the_planted_node() {
        List<IdleGap> gaps = oracle.idleAssumptionGaps(ENGINE_IDLE_RATIO);
        assertEquals(8, gaps.size(), "노드 8대 전부에 대해 격차를 낸다");

        IdleGap worst = gaps.stream().max(Comparator.comparingDouble(IdleGap::absoluteGapWatts)).orElseThrow();
        assertEquals("busan-02:9100", worst.nodeId(),
                "|실제 − 가정| 최대는 busan-02 — 실제 380W 대 가정 500×0.3 = 150W, 격차 230W");
        assertEquals(380.0, worst.declaredIdleWatts(), EPS, "파일에 적힌 실제 유휴 380W");
        assertEquals(150.0, worst.engineAssumedIdleWatts(), EPS, "엔진 가정 = 정격 500 × 0.3 = 150W");
        assertEquals(230.0, worst.absoluteGapWatts(), EPS, "격차 380 − 150 = 230W");
        assertEquals(0.76, worst.declaredIdleRatio(), EPS,
                "실제 유휴 비율 380/500 = 0.76 — 엔진이 믿는 0.30 의 2.5배");
    }

    @DisplayName("여덟 대 중 네 대는 엔진 가정과 정확히 같고 한 대가 0_02 안에 더 있다")
    @Test
    void four_of_eight_nodes_match_the_engine_assumption() {
        List<IdleGap> gaps = oracle.idleAssumptionGaps(ENGINE_IDLE_RATIO);

        long within01 = gaps.stream().filter(g -> Math.abs(g.declaredIdleRatio() - 0.30) <= 0.01).count();
        assertEquals(4, within01,
                "유휴 비율이 0.30±0.01 인 노드는 seoul-03·busan-03·daejeon-01·daejeon-02 4대 — "
                        + "150/500 = 0.30, 120/400 = 0.30 으로 정확히 엔진 가정과 같다");

        long within02 = gaps.stream().filter(g -> Math.abs(g.declaredIdleRatio() - 0.30) <= 0.02).count();
        assertEquals(5, within02,
                "범위를 0.02 로 넓히면 busan-01(140/500 = 0.28)이 더해져 5대 — 합성 데이터의 다수가 "
                        + "엔진 모델(정격×0.3)로 생성됐다는 증거이고, 오라클이 엔진과 다른 값을 내는 노드는 소수다");

        assertEquals(0.28, gaps.stream().filter(g -> g.nodeId().equals("busan-01:9100")).findFirst()
                .orElseThrow().declaredIdleRatio(), EPS, "busan-01 은 140/500 = 0.28 로 0.30 에서 0.02 떨어져 있다");
        assertEquals(3, gaps.stream().filter(g -> Math.abs(g.declaredIdleRatio() - 0.30) > 0.02).count(),
                "가정에서 0.02 넘게 벗어난 노드는 seoul-01(0.2667)·seoul-02(0.2444)·busan-02(0.76) 3대다");
    }

    @DisplayName("우리가 심은 노드가 코어당 생성식 비용이 가장 싸다")
    @Test
    void planted_node_is_the_cheapest_per_core() {
        List<IdleGap> gaps = oracle.idleAssumptionGaps(ENGINE_IDLE_RATIO);
        List<IdleGap> byCost = gaps.stream()
                .sorted(Comparator.comparingDouble(IdleGap::generatorWattsPerCore)).toList();

        assertEquals("busan-02:9100", byCost.get(0).nodeId(),
                "코어당 동적 전력 최소는 busan-02 — (500−380)/24 = 5.0 W/코어");
        assertEquals(5.0, byCost.get(0).generatorWattsPerCore(), EPS, "(정격 500 − 유휴 380) / 24코어 = 5.0");
        assertEquals("seoul-01:9100", byCost.get(1).nodeId(), "2위는 seoul-01 — (600−160)/32 = 13.75 W/코어");
        assertEquals(13.75, byCost.get(1).generatorWattsPerCore(), EPS, "(정격 600 − 유휴 160) / 32코어 = 13.75");

        assertTrue(byCost.get(0).generatorWattsPerCore() < byCost.get(1).generatorWattsPerCore(),
                "동적분만 보면 busan-02 가 가장 싸다 — 그런데 유휴 380W 를 얹으면 가장 비싼 노드가 된다. "
                        + "이 역전이 전원 제어 두 판을 따로 계산해야 하는 이유다");
    }

    @DisplayName("노드 온도는 생성식과 같다")
    @Test
    void temperature_follows_the_generator_formula() {
        assertEquals(77.5, oracle.nodeTemperatureC("seoul-03:9100", 85), EPS,
                "seoul-03 은 기준 온도 52℃ — 52 + 0.3 × 85 = 77.5℃");
        assertEquals(52.0, oracle.nodeTemperatureC("seoul-03:9100", 0), EPS,
                "CPU 0% 면 기준 온도 52℃ 그대로");
        assertEquals(70.0, oracle.nodeTemperatureC("busan-03:9100", 100), EPS,
                "미계측 노드도 물리적으로는 뜨거워진다 — 40 + 0.3 × 100 = 70.0℃. "
                        + "엔진이 못 보는 것과 온도가 없는 것은 다르다");
    }

    @DisplayName("모르는 노드는 조용히 0 을 돌려주지 않는다")
    @Test
    void unknown_node_fails_loudly() {
        String unknown = "seoul-99:9100";

        IllegalStateException power = assertThrows(IllegalStateException.class,
                () -> oracle.nodePowerWatts(unknown, 50));
        assertTrue(power.getMessage().contains(unknown), "메시지에 노드 id 가 있어야 한다: " + power.getMessage());

        IllegalStateException temperature = assertThrows(IllegalStateException.class,
                () -> oracle.nodeTemperatureC(unknown, 50));
        assertTrue(temperature.getMessage().contains(unknown), "온도도 같은 자리에서 실패한다");

        IllegalStateException marginal = assertThrows(IllegalStateException.class,
                () -> oracle.marginalPowerWatts(unknown, 2, true, PowerControl.UNAVAILABLE));
        assertTrue(marginal.getMessage().contains(unknown), "증분도 같은 자리에서 실패한다");

        IllegalStateException cluster = assertThrows(IllegalStateException.class,
                () -> oracle.clusterPowerWatts(Map.of(unknown, 10.0), Set.of(), PowerControl.UNAVAILABLE));
        assertTrue(cluster.getMessage().contains(unknown),
                "총전력 계산에 모르는 노드가 섞이면 그 노드를 빼고 합산하지 않고 실패한다");
    }
}

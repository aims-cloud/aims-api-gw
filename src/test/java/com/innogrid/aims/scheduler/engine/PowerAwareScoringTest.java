package com.innogrid.aims.scheduler.engine;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 전력 엔진 점수식이 고치려 한 결함들이 실제로 사라졌는지 확인한다.
 *
 * <p>각 테스트는 재작성 전 코드에서 <b>실패했을</b> 상황을 재현한다. 값은 손으로 검산할 수 있게
 * 잡았다 — 용량 500W, 유휴 비율 0.3(P_idle 150W), 동적 범위 350W, 8코어, 2코어 워크로드이면
 * 동적 증가분이 87.5W 다.</p>
 */
class PowerAwareScoringTest {

    private final PowerAwareScheduler scheduler = new PowerAwareScheduler();

    // ── 결함 A — 비례성이 저전력 노드를 벌하던 문제 ────────────────

    /**
     * 이상 곡선(360W)보다 적게 쓰는 200W 노드가, 넘겨 쓰는 440W 노드보다 높아야 한다.
     * 옛 수식은 대칭 편차를 써서 200W 를 0.68, 440W 를 0.84 로 매겼다.
     */
    @DisplayName("전력을 적게 쓰는 노드를 벌하지 않는다")
    @Test
    void low_power_node_not_penalized() {
        List<NodeResource> nodes = List.of(
                node("frugal", 60, 50, 200, 55),
                node("overdraw", 60, 50, 440, 55));

        SchedulingResult result = scheduler.schedule(workload(2), nodes, policy());

        assertEquals("frugal", result.selectedNodeId());
        assertTrue(detail(result, "frugal", "비낭비") > detail(result, "overdraw", "비낭비"));
    }

    // ── 결함 B·C·M3 — 한계 전력이 결정에 반영되고 노드를 구별한다 ──

    /**
     * M3 — 빈 노드에 처음 얹으면 그 노드의 유휴 전력이 새로 든다.
     * 옛 수식은 둘 다 87.5W 로 봐서 통합할 이유를 설명하지 못했다.
     */
    @DisplayName("빈 노드에는 유휴 기동분이 더해진다")
    @Test
    void idle_node_adds_startup_power() {
        List<NodeResource> nodes = List.of(
                node("running", 50, 50, 250, 55),
                node("parked", 2, 50, 250, 55));

        SchedulingResult result = scheduler.schedule(workload(2), nodes, policy());

        assertEquals(87.5, detail(result, "running", "실질한계전력W"), 1e-4);
        // parked 는 계측(250W)·유휴(2%)라 깨우기 비용을 실측에서 역산한다: (250 − 500×0.02)/(1−0.02) = 244.9W.
        // 대체값 150W(정격×0.3)가 아니다 — 이 픽스처는 놀면서 250W 를 쓰는 노드라 깨우는 값도 그만큼이다.
        assertEquals(87.5 + (250 - 500 * 0.02) / 0.98, detail(result, "parked", "실질한계전력W"), 1e-4,
                "실측에서 역산한 유휴 전력 244.9W 가 더해진다");
        assertEquals(1.0, detail(result, "running", "가동중"), 1e-9);
        assertEquals(0.0, detail(result, "parked", "가동중"), 1e-9);
    }

    /** 결함 C — 볼록 지수를 올리면 같은 사양 노드도 현재 부하에 따라 갈린다. */
    @DisplayName("볼록 지수를 올리면 부하가 높은 노드의 한계전력이 커진다")
    @Test
    void convex_raises_marginal_power() {
        SchedulerProperties props = new SchedulerProperties();
        props.getPower().setCurveAlpha(1.8);
        PowerAwareScheduler convex = new PowerAwareScheduler(props);

        List<NodeResource> nodes = List.of(
                node("busy", 70, 50, 250, 55),
                node("light", 20, 50, 250, 55));

        SchedulingResult result = convex.schedule(workload(2), nodes, policy());

        assertTrue(detail(result, "busy", "실질한계전력W") > detail(result, "light", "실질한계전력W"),
                "바쁜 노드에 얹으면 전력이 더 든다");
    }

    // ── 결함 D — 워크로드 크기가 결정에 영향을 준다 ────────────────

    /** 옛 수식은 워크로드를 표시용으로만 써서 1코어든 6코어든 순위가 같았다. */
    @DisplayName("워크로드 크기가 점수를 움직인다")
    @Test
    void workload_size_moves_score() {
        List<NodeResource> nodes = List.of(node("n1", 40, 40, 250, 55));

        double small = scheduler.schedule(workload(1), nodes, policy()).finalScore();
        double large = scheduler.schedule(workload(4), nodes, policy()).finalScore();

        assertTrue(small > large, "많이 요구할수록 전력이 더 늘어 점수가 낮아야 한다: " + small + " vs " + large);
    }

    // ── 결함 E — 미수집 노드를 우대하지 않는다 ────────────────────

    /**
     * 전력·온도가 0 이면 판단 근거가 없다. 0 점을 주면 최악으로, 감점을 안 하면 최상으로
     * 평가된다. 둘 다 틀렸으므로 중립 0.5 를 준다.
     */
    @DisplayName("전력을 수집하지 못하면 중립값을 쓴다")
    @Test
    void unmeasured_power_neutral() {
        List<NodeResource> nodes = List.of(node("no-ipmi", 40, 40, 0, 0));

        SchedulingResult result = scheduler.schedule(workload(2), nodes, policy());

        assertEquals(0.5, detail(result, "no-ipmi", "전력점수"), 1e-9);
        assertEquals(0.5, detail(result, "no-ipmi", "열점수"), 1e-9);
    }

    /** 전력 메트릭이 하나도 없으면 근거 문장이 그 사실을 밝혀야 한다. */
    @DisplayName("전력 메트릭이 없으면 근거에 밝힌다")
    @Test
    void missing_power_metric_in_details() {
        List<NodeResource> nodes = List.of(
                node("a", 20, 30, 0, 0),
                node("b", 60, 70, 0, 0));

        SchedulingResult result = scheduler.schedule(workload(2), nodes, policy());

        assertTrue(result.reason().contains("전력 메트릭이 없어"), result.reason());
        assertEquals("a", result.selectedNodeId(), "이때는 자원 여유로 결정된다");
    }

    /** 계측된 노드가 하나라도 있으면 클러스터 요약을 근거에 담는다. */
    @DisplayName("계측된 노드가 있으면 클러스터 요약을 남긴다")
    @Test
    void measured_leaves_cluster_summary() {
        List<NodeResource> nodes = List.of(
                node("measured", 40, 40, 250, 50),
                node("parked", 1, 10, 150, 40));

        SchedulingResult result = scheduler.schedule(workload(2), nodes, policy());

        assertTrue(result.reason().contains("클러스터"), result.reason());
        assertTrue(result.reason().contains("반납가능"), result.reason());
    }

    // ── 결함 G — 점수가 CPU 에 대해 단조롭다 ──────────────────────

    /**
     * 옛 수식은 PEI(증가)와 CPU 여유(감소)가 맞붙어 30% 부근에서 꺾였다. 꺾이면 "왜 이 노드가
     * 뽑혔는지" 설명할 수 없다. 방향이 증가인 것은 통합 목적상 의도된 것이다 — 같은 전력에서
     * CPU 가 높으면 단위 전력당 처리량이 좋다. 몰림은 사용률 임계가 막는다.
     */
    @DisplayName("점수는 CPU 사용률에 대해 단조롭다")
    @Test
    void score_monotonic_in_cpu() {
        List<Double> series = new ArrayList<>();
        for (int cpu = 10; cpu <= 70; cpu += 5) {
            List<NodeResource> nodes = List.of(node("n", cpu, 50, 250, 55));
            series.add(scheduler.schedule(workload(2), nodes, policy()).finalScore());
        }

        int flips = 0;
        for (int i = 2; i < series.size(); i++) {
            double prev = series.get(i - 1) - series.get(i - 2);
            double curr = series.get(i) - series.get(i - 1);
            if (prev * curr < 0) flips++;
        }

        assertEquals(0, flips, "증감 방향이 바뀌면 안 된다: " + series);
    }

    // ── 결함 H — 정책 가중치와 필드 사용 ──────────────────────────

    /** 정책의 전력:온도 비율이 실제로 반영된다. 옛 코드는 정책 가중치를 아예 보지 않았다. */
    @DisplayName("정책의 전력 온도 비율이 반영된다")
    @Test
    void policy_power_temp_ratio_applied() {
        List<NodeResource> nodes = List.of(
                node("power-good", 60, 50, 200, 70),   // 전력 효율 좋고 뜨겁다
                node("cool-poor", 20, 50, 400, 35));   // 시원하지만 전력 낭비

        SchedulingPolicy powerFirst = policy();
        powerFirst.setPowerWeight(0.90);
        powerFirst.setTemperatureWeight(0.05);

        SchedulingPolicy thermalFirst = policy();
        thermalFirst.setPowerWeight(0.05);
        thermalFirst.setTemperatureWeight(0.90);

        assertEquals("power-good", scheduler.schedule(workload(2), nodes, powerFirst).selectedNodeId());
        assertEquals("cool-poor", scheduler.schedule(workload(2), nodes, thermalFirst).selectedNodeId());
    }

    /**
     * `networkScore` 칸에는 실측 네트워크 점수를 담는다.
     * 0 을 넣으면 "해당 없음" 이 아니라 "네트워크 포화" 로 읽히고, weighted 엔진이 같은 칸에
     * 담는 값과도 의미가 어긋난다.
     */
    @DisplayName("networkScore에 실측 네트워크 점수가 담긴다")
    @Test
    void networkscore_carries_measured() {
        List<NodeResource> nodes = List.of(node("n1", 40, 40, 250, 55));

        NodeScore power = scheduler.schedule(workload(2), nodes, policy()).candidateScores().get(0);
        NodeScore weighted = new WeightedScoreScheduler()
                .schedule(workload(2), nodes, policy()).candidateScores().get(0);

        assertEquals(weighted.networkScore(), power.networkScore(), 1e-9,
                "두 엔진이 같은 칸에 같은 의미의 값을 담아야 한다");
        assertNotEquals(0.0, power.networkScore(), "0 은 포화로 오해된다");
    }

    /** 점수에 쓰지 않는 값도 판단 근거로는 남긴다. */
    @DisplayName("점수에 쓰지 않는 값도 details에 남긴다")
    @Test
    void unused_values_kept_in_details() {
        List<NodeResource> nodes = List.of(node("n1", 40, 40, 250, 55));

        NodeScore top = scheduler.schedule(workload(2), nodes, policy()).candidateScores().get(0);

        assertTrue(top.details().containsKey("네트워크한산도"));
        assertTrue(top.details().containsKey("가동중"));
        assertTrue(top.details().containsKey("실질한계전력W"));
    }

    // ── 계측되지 않은 값의 표시 ──────────────────────────────────

    /**
     * 계측하지 못한 값에서 파생된 항목은 숫자를 채우지 않는다.
     * 0 이나 0.5 를 넣으면 "측정했더니 그 값" 으로 읽혀 판단 근거가 없다는 사실이 가려진다.
     */
    @DisplayName("계측되지 않으면 파생 항목을 null로 둔다")
    @Test
    void unmeasured_derived_fields_null() {
        List<NodeResource> nodes = List.of(node("no-ipmi", 40, 40, 0, 0));

        NodeScore s = scheduler.schedule(workload(2), nodes, policy()).candidateScores().get(0);

        assertEquals(0.0, s.details().get("전력계측"), 1e-9);
        assertEquals(0.0, s.details().get("온도계측"), 1e-9);
        assertNull(s.details().get("PEI"), "실측 전력으로 나누는 값이라 낼 수 없다");
        assertNull(s.details().get("비낭비"), "이상 곡선과 비교할 실측값이 없다");
        assertNull(s.details().get("배치후예상전력W"), "기준 전력을 모르면 절대값을 낼 수 없다");
        assertNull(s.details().get("배치후예상온도C"), "0℃ 로 오해된다");

        // 키는 그대로 있어야 한다 — 빠지면 노드마다 항목 구성이 달라진다
        for (String key : List.of("PEI", "비낭비", "배치후예상전력W", "배치후예상온도C")) {
            assertTrue(s.details().containsKey(key), key + " 키는 남아 있어야 한다");
        }
    }

    /**
     * 실질 한계 전력은 실측 전력을 쓰지 않는다 — 용량과 CPU 사용률만으로 구하는 증분이라
     * 계측이 없어도 성립한다. 낼 수 있는 값까지 비우면 정보를 버리는 것이다.
     */
    @DisplayName("계측이 없어도 한계 전력 증분은 낸다")
    @Test
    void marginal_power_without_measurement() {
        List<NodeResource> nodes = List.of(node("no-ipmi", 40, 40, 0, 0));

        NodeScore s = scheduler.schedule(workload(2), nodes, policy()).candidateScores().get(0);

        assertNotNull(s.details().get("실질한계전력W"));
        assertEquals(87.5, s.details().get("실질한계전력W"), 1e-4, "용량 500W · 8코어 중 2코어");
        assertNotNull(s.details().get("실질한계전력점수"));
        assertEquals(0.5, s.details().get("전력점수"), 1e-9, "점수는 의도된 중립값이라 채운다");
    }

    /** 계측된 노드는 모든 항목이 채워진다. */
    @DisplayName("계측되면 모든 항목이 채워진다")
    @Test
    void measured_fills_every_field() {
        List<NodeResource> nodes = List.of(node("ipmi", 40, 40, 250, 55));

        NodeScore s = scheduler.schedule(workload(2), nodes, policy()).candidateScores().get(0);

        assertEquals(1.0, s.details().get("전력계측"), 1e-9);
        assertEquals(1.0, s.details().get("온도계측"), 1e-9);
        for (String key : s.details().keySet()) {
            assertNotNull(s.details().get(key), key + " 가 비어 있다");
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────

    private Double detail(SchedulingResult result, String nodeId, String key) {
        return result.candidateScores().stream()
                .filter(s -> s.nodeId().equals(nodeId))
                .findFirst().orElseThrow()
                .details().get(key);
    }

    private SchedulingPolicy policy() {
        return new SchedulingPolicy("test", "test");
    }

    private Workload workload(int cpu) {
        return new Workload("scoring-job", cpu, 2048);
    }

    private NodeResource node(String nodeId, double cpuUsage, double memUsage,
                              double power, double temp) {
        return new NodeResource("mdc-01", nodeId, 8, 16384, 0,
                cpuUsage, memUsage, 40, power, 500, temp, 1_000_000, 500_000);
    }
}

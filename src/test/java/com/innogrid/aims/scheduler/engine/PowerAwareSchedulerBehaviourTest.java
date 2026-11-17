package com.innogrid.aims.scheduler.engine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 전력 엔진의 <b>관찰 가능한 계약</b>을 고정한다.
 *
 * <p>{@link PowerAwareSchedulerTest}는 "어느 노드가 뽑히는가"라는 결론만 본다. 점수식을 다시
 * 쓸 때는 결론이 같아도 순위·응답 구조가 조용히 달라질 수 있어, 그것까지 여기서 못 박는다.</p>
 *
 * <p>점수의 <b>절대값</b>은 고정하지 않는다. 수식을 개선하면 값은 당연히 달라지고, 값을 박아두면
 * 개선할 때마다 기대값을 고쳐야 해서 회귀 그물 구실을 못 한다. 대신 <b>깨지면 안 되는 성질</b>을
 * 고정한다 — 후보 수, 정렬 순서, 제외 노드 처리, 근거 항목의 존재, 점수 범위.</p>
 */
class PowerAwareSchedulerBehaviourTest {

    private final PowerAwareScheduler scheduler = new PowerAwareScheduler();

    /** 모든 후보가 결과에 담기고, 점수 내림차순으로 정렬된다. */
    @DisplayName("후보 전체가 점수 내림차순으로 담긴다")
    @Test
    void candidates_sorted_by_score_desc() {
        SchedulingResult result = scheduler.schedule(workload(2), threeNodes(), policy());

        assertTrue(result.success());
        assertEquals(3, result.candidateScores().size(), "제외된 노드도 근거로 남겨야 한다");

        List<NodeScore> scores = result.candidateScores();
        for (int i = 1; i < scores.size(); i++) {
            assertTrue(scores.get(i - 1).totalScore() >= scores.get(i).totalScore(),
                    "점수 내림차순이어야 한다: " + scores);
        }
    }

    /** 선택된 노드는 배치 가능한 후보 중 최고점이어야 한다. */
    @DisplayName("선택된 노드는 배치 가능한 최고점이다")
    @Test
    void selected_is_highest_placeable() {
        SchedulingResult result = scheduler.schedule(workload(2), threeNodes(), policy());

        NodeScore best = result.candidateScores().stream()
                .filter(NodeScore::eligible)
                .findFirst().orElseThrow();

        assertEquals(best.nodeId(), result.selectedNodeId());
        assertEquals(best.totalScore(), result.finalScore(), 1e-9);
    }

    /** 점수는 0~1 범위를 벗어나지 않는다. 음수 점수는 표시도 비교도 곤란하다. */
    @DisplayName("점수는 0과 1 사이다")
    @Test
    void score_between_0_and_1() {
        SchedulingResult result = scheduler.schedule(workload(2), threeNodes(), policy());

        for (NodeScore s : result.candidateScores()) {
            assertTrue(s.totalScore() >= 0.0 && s.totalScore() <= 1.0,
                    s.nodeId() + " 점수가 범위를 벗어났다: " + s.totalScore());
        }
    }

    /**
     * 판단 근거를 {@code details}로 남긴다.
     *
     * <p>항목 이름은 수식을 바꾸면 함께 바뀌므로 고정하지 않는다. 대신 <b>비어 있지 않을 것</b>과
     * 전력 엔진이라면 반드시 설명해야 하는 두 가지 — 전력 효율과 배치 후 예상 전력 — 이
     * 들어 있는지만 본다.</p>
     */
    @DisplayName("판단 근거를 details 로 남긴다")
    @Test
    void basis_recorded_in_details() {
        SchedulingResult result = scheduler.schedule(workload(2), threeNodes(), policy());
        NodeScore top = result.candidateScores().get(0);

        assertTrue(top.details().size() >= 5, "근거가 너무 적다: " + top.details());
        assertTrue(top.details().containsKey("PEI"), "전력 효율 지표는 남겨야 한다");
        assertTrue(top.details().keySet().stream().anyMatch(k -> k.contains("배치후예상전력")),
                "배치 후 예상 전력은 남겨야 한다: " + top.details().keySet());
    }

    /** 제약을 어긴 노드는 사유와 함께 남고 선택되지 않는다. */
    @DisplayName("제외된 노드는 사유와 함께 남는다")
    @Test
    void excluded_nodes_have_reason() {
        SchedulingPolicy policy = policy();
        policy.setMaxCpuUsagePercent(70);

        // 코어를 넉넉히 줘 C1(자원 가용성)을 통과시켜야 C2(사용률 임계)에 도달한다.
        // 8코어에 90%를 쓰면 가용이 0.8 코어뿐이라 C1이 먼저 걸린다.
        List<NodeResource> nodes = List.of(
                bigNode("busy", 90, 40, 250, 55),   // CPU 90% > 70% → C2로 제외
                bigNode("calm", 30, 40, 250, 55));

        SchedulingResult result = scheduler.schedule(workload(2), nodes, policy);

        NodeScore busy = result.candidateScores().stream()
                .filter(s -> s.nodeId().equals("busy")).findFirst().orElseThrow();

        assertEquals("calm", result.selectedNodeId());
        assertTrue(!busy.eligible());
        assertNotNull(busy.exclusionReason());
        assertTrue(busy.exclusionReason().startsWith("CPU 사용률 초과"), busy.exclusionReason());
    }

    /** 배치 가능한 노드가 하나도 없으면 실패하되 근거는 남긴다. */
    @DisplayName("전부 제외되면 실패하고 근거를 남긴다")
    @Test
    void all_excluded_fails_with_basis() {
        SchedulingPolicy policy = policy();
        policy.setMaxCpuUsagePercent(1);   // 가장 한산한 노드(CPU 5%)까지 걸리는 값

        SchedulingResult result = scheduler.schedule(workload(2), threeNodes(), policy);

        assertTrue(!result.success());
        assertEquals(3, result.candidateScores().size(), "실패해도 왜 안 되는지는 보여야 한다");
        assertNotNull(result.reason());
    }

    /** 후보가 비면 즉시 실패한다. */
    @DisplayName("후보가 없으면 실패한다")
    @Test
    void no_candidates_fails() {
        SchedulingResult result = scheduler.schedule(workload(2), List.of(), policy());

        assertTrue(!result.success());
        assertTrue(result.candidateScores().isEmpty());
    }

    /** 같은 입력이면 같은 결과가 나온다. 배치 결정은 재현 가능해야 한다. */
    @DisplayName("같은 입력에 같은 결과가 나온다")
    @Test
    void same_input_same_result() {
        SchedulingResult a = scheduler.schedule(workload(2), threeNodes(), policy());
        SchedulingResult b = scheduler.schedule(workload(2), threeNodes(), policy());

        assertEquals(a.selectedNodeId(), b.selectedNodeId());
        assertEquals(a.finalScore(), b.finalScore(), 1e-9);
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private SchedulingPolicy policy() {
        return new SchedulingPolicy("test", "test");
    }

    private Workload workload(int cpu) {
        return new Workload("snapshot-job", cpu, 2048);
    }

    /** 전력 효율·부하·온도가 서로 다른 3노드. */
    private List<NodeResource> threeNodes() {
        return List.of(
                node("efficient", 60, 50, 200, 50),
                node("wasteful", 30, 50, 400, 50),
                node("cool-idle", 5, 10, 300, 35));
    }

    /** 코어가 넉넉해 사용률이 높아도 C1이 아니라 C2로 걸리는 노드. */
    private NodeResource bigNode(String nodeId, double cpuUsage, double memUsage,
                                 double power, double temp) {
        return new NodeResource("mdc-01", nodeId, 64, 131072, 0,
                cpuUsage, memUsage, 40, power, 500, temp, 1_000_000, 500_000);
    }

    private NodeResource node(String nodeId, double cpuUsage, double memUsage,
                              double power, double temp) {
        return new NodeResource("mdc-01", nodeId, 8, 16384, 0,
                cpuUsage, memUsage, 40, power, 500, temp, 1_000_000, 500_000);
    }
}

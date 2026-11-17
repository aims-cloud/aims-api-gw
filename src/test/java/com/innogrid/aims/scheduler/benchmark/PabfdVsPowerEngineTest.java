package com.innogrid.aims.scheduler.benchmark;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PABFD와 우리 전력 엔진이 <b>실제로 다른 답을 낸다</b>는 것을 고정한다.
 *
 * <p>이 테스트가 없으면 "ΔP 단독 대조군과 비교했다"는 문장이 위험하다. 두 전략이 같은 노드를 고르면 표의
 * 두 열은 같은 숫자가 되고, 그걸 "우리가 대조군과 동등하다"고 읽든 "차이가 없다"고 읽든 둘 다
 * 근거가 없다. 차이가 <b>어디서</b> 나는지 — 유휴 노드를 깨우는 비용을 세는가 — 를 두 수식의
 * 손계산으로 먼저 못 박고, 그 차이가 배치 결정까지 이어지는 것을 확인한다.</p>
 *
 * <pre>
 * 픽스처 (8코어 · 2코어 워크로드 · 유휴 비율 0.3)
 *   idle-x : 정격 500W, 실측 160W(유휴 150W 근처), CPU 2%   → 사실상 빈 노드
 *   busy-y : 정격 900W, 실측 500W,                CPU 50%  → 가동 중
 *
 *   marginalPower     (고전 선형, 깨우기 비용 없음)   idle-x  87.5W  <  busy-y 157.5W
 *   realMarginalPower (유휴 가산 — 계측된 유휴 노드는 실측 역산 153.1W를 절편·깨우기항에)  idle-x 239.8W  >  busy-y 157.5W
 * </pre>
 *
 * <p>두 수식의 부등호가 <b>반대</b>다. 그래서 PABFD는 빈 노드를, 우리 엔진은 가동 중인 노드를
 * 고른다. 정격이 큰 서버가 동적 증가분도 크다는 사실만 보면 빈 노드가 유리해 보이지만, 클러스터 총
 * 전력은 "가동 노드의 유휴 전력 + 동적 전력"이므로 빈 노드를 깨우면 그 노드의 유휴 전력(실측 160W에서 역산한 153W)이 통째로 새로 든다.</p>
 */
class PabfdVsPowerEngineTest {

    private final PowerModel powerModel = new PowerModel(new SchedulerProperties());

    // ── 1단계: 두 수식이 갈리는지 자체를 확인한다 ─────────────────

    /**
     * 손계산.
     * <pre>
     * idle-x  동적 = (500 − 500×0.3) × 2/8 = 350 × 0.25 = 87.5W
     * busy-y  동적 = (900 − 900×0.3) × 2/8 = 630 × 0.25 = 157.5W
     * </pre>
     * 정격이 큰 노드가 같은 코어 비율에서 더 많이 오른다 — 그래서 고전 모델은 작은 서버를 선호한다.
     */
    @DisplayName("고전 한계전력은 정격만 보므로 빈 노드가 더 싸게 보인다")
    @Test
    void classic_marginal_power_favours_the_idle_node() {
        NodeResource idle = idleNode();
        NodeResource busy = busyNode();

        assertEquals(87.5, powerModel.marginalPower(idle, 2), 1e-9, "350W 동적 범위의 25%");
        assertEquals(157.5, powerModel.marginalPower(busy, 2), 1e-9, "630W 동적 범위의 25%");
        assertTrue(powerModel.marginalPower(idle, 2) < powerModel.marginalPower(busy, 2));
    }

    /**
     * 손계산.
     * <pre>
     * idle-x  CPU 2% ≤ 활성 기준 5% → 빈 노드 → (500 − 153.1) × 0.25 = 86.7 + 역산 유휴 153.1 = 239.8W (절편도 역산 유휴, 대체값 150 아님)
     * busy-y  CPU 50% > 5%        → 가동 중  → 157.5W
     * </pre>
     */
    @DisplayName("실질 한계전력은 빈 노드를 깨우는 유휴 전력을 더한다")
    @Test
    void real_marginal_power_charges_for_waking_the_idle_node() {
        NodeResource idle = idleNode();
        NodeResource busy = busyNode();

        // idle-x는 계측 160W·CPU 2%라 깨우기 비용을 실측에서 역산한다: (160 − 500×0.02)/(1−0.02) = 153.1W.
        // pabfd가 쓰는 marginalPower(대체값 기준 87.5W)는 그대로다 — 갈리는 것은 깨우기 비용의 출처뿐이다.
        double recon = (160 - 500 * 0.02) / 0.98;
        assertEquals((500 - recon) * 0.25 + recon, powerModel.realMarginalPower(idle, 2), 1e-9,
                "동적 (500 − 153.1) × 0.25 = 86.7 + 역산 유휴 153.1W");
        assertEquals(157.5, powerModel.realMarginalPower(busy, 2), 1e-9, "이미 도는 노드라 동적 증가분만");
        assertTrue(powerModel.realMarginalPower(idle, 2) > powerModel.realMarginalPower(busy, 2));
    }

    /** 두 수식의 최소값이 서로 다른 노드를 가리킨다 — 이 판이 비교의 의미를 갖는 조건이다. */
    @DisplayName("두 수식의 최소값이 서로 다른 노드를 가리킨다")
    @Test
    void the_two_formulas_point_at_different_nodes() {
        NodeResource idle = idleNode();
        NodeResource busy = busyNode();

        String classicMin = powerModel.marginalPower(idle, 2) < powerModel.marginalPower(busy, 2)
                ? idle.getNodeId() : busy.getNodeId();
        String realMin = powerModel.realMarginalPower(idle, 2) < powerModel.realMarginalPower(busy, 2)
                ? idle.getNodeId() : busy.getNodeId();

        assertNotEquals(classicMin, realMin,
                "두 수식이 같은 노드를 가리키면 이 픽스처로는 PABFD 와 우리 엔진을 구분할 수 없다");
        assertEquals("idle-x", classicMin);
        assertEquals("busy-y", realMin);
    }

    // ── 2단계: 그 차이가 배치 결정까지 이어진다 ──────────────────

    /**
     * PABFD는 {@code marginalPower}의 최소값을 고르므로 빈 노드를 깨운다.
     *
     * <p>정규화 점수는 두 노드가 <b>똑같이 0.825</b>다 — ΔP/정격 = 0.7 × 요구코어/총코어 라서 코어
     * 비율이 같으면 정격이 달라도 같은 값이 나온다. 선택은 정규화 점수가 아니라 <b>절대 와트</b>로
     * 하며, 그 와트를 {@code details}에 남기는 이유가 여기 있다.</p>
     */
    @DisplayName("PABFD는 빈 노드를 고른다")
    @Test
    void pabfd_wakes_the_idle_node() {
        Workload workload = workload();

        SchedulingResult result = BaselinePlacers.pabfd(powerModel)
                .placer().place(workload, PlacementContext.of(nodes(), policy()));

        assertEquals("idle-x", result.selectedNodeId());
        assertEquals(0.825, result.finalScore(), 1e-9, "1 − 87.5/500");
        assertEquals(87.5, detail(result, "idle-x"), 1e-9);
        assertEquals(157.5, detail(result, "busy-y"), 1e-9);
        assertTrue(result.reason().contains("깨우는 비용을 세지 않는다"), result.reason());
    }

    /**
     * 우리 전력 엔진은 가동 중인 노드를 고른다.
     *
     * <p>총점 손계산(정책 기본값 · 자원 몫 0.10):</p>
     * <pre>
     * idle-x  전력점수 .40×0.025 + .25×0.9940 + .35×0.5204 = 0.44064
     * busy-y  전력점수 .40×0.200 + .25×1.0000 + .35×0.6850 = 0.56975
     * 열점수  둘 다 1 − (40/75)² = 0.715556 (경고 65℃ 미만이라 감점 0)
     * 총점    idle-x 0.5966  <  busy-y 0.6190
     * </pre>
     * 자원 여유만 보면 빈 노드가 0.94 대 0.50으로 앞서지만, 자원 몫이 0.10이라 전력 판단이 이긴다.
     * busy-y의 한계전력 점수 0.685는 1 − 157.5/500이다 — 분모가 정격 900이 아니라 W 눈금이라
     * 정격이 큰 노드의 깨우기·증가 비용이 그만큼 크게 보인다. idle-x는 정격이 눈금과 같아 값이 그대로다.
     */
    @DisplayName("전력 엔진은 가동 중인 노드를 고른다")
    @Test
    void power_engine_consolidates_onto_the_running_node() {
        SchedulingResult result = new PowerAwareScheduler().schedule(workload(), nodes(), policy());

        assertEquals("busy-y", result.selectedNodeId());
        assertEquals(0.6190, result.finalScore(), 1e-4);
    }

    /** 같은 판·같은 워크로드에서 두 전략의 답이 다르다는 것 한 줄. 표의 두 열이 같은 숫자가 아니라는 근거다. */
    @DisplayName("같은 판에서 PABFD와 전력 엔진의 답이 다르다")
    @Test
    void pabfd_and_power_engine_disagree() {
        String pabfd = BaselinePlacers.pabfd(powerModel).placer()
                .place(workload(), PlacementContext.of(nodes(), policy())).selectedNodeId();
        String engine = new PowerAwareScheduler().schedule(workload(), nodes(), policy()).selectedNodeId();

        assertNotEquals(pabfd, engine);
        assertEquals("idle-x", pabfd);
        assertEquals("busy-y", engine);
    }

    // ── 픽스처 ───────────────────────────────────────────────

    private static List<NodeResource> nodes() {
        return List.of(busyNode(), idleNode());   // nodeId 순은 busy-y < idle-x — 정렬이 결과를 바꾸지 않음을 겸한다
    }

    /** 사실상 빈 노드. 실측 160W는 유휴 150W를 조금 넘는 값이라 "켜져 있지만 일하지 않는" 상태다. */
    private static NodeResource idleNode() {
        return new NodeResource("mdc-01", "idle-x", 8, 16384, 0,
                2, 10, 40, 160, 500, 40, 1_000_000, 500_000);
    }

    /** 가동 중인 큰 노드. 정격 900W라 동적 증가분은 크지만 새로 드는 유휴 전력이 없다. */
    private static NodeResource busyNode() {
        return new NodeResource("mdc-01", "busy-y", 8, 16384, 0,
                50, 50, 40, 500, 900, 40, 1_000_000, 500_000);
    }

    private static Workload workload() {
        return new Workload("pabfd-vs-power", 2, 4096);
    }

    private static SchedulingPolicy policy() {
        return new SchedulingPolicy("benchmark", "PABFD 대조용");
    }

    private Double detail(SchedulingResult result, String nodeId) {
        return result.candidateScores().stream()
                .filter(s -> s.nodeId().equals(nodeId))
                .findFirst().orElseThrow()
                .details().get("한계전력W");
    }
}

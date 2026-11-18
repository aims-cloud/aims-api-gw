package com.innogrid.aims.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse.Mdc;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.MdcPowerBudget;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.model.NodeResource;

/**
 * 전력 최적화 진단의 MDC별 요약 — 예산이 없어도 현황은 나오고, 있으면 남은 여유·초과가 함께 나온다.
 *
 * <p>핵심은 <b>진단의 남은 여유와 배치 판정(C6)이 같은 기준선을 쓰는가</b>다. 실측만 보면 "여유가 있다"고
 * 보여 주고도 실제 배치는 막히는 어긋남이 생긴다 — 예약분을 한쪽만 보기 때문이다.</p>
 */
@SpringBootTest
class PowerOptimizationMdcTest {

    @Autowired PowerOptimizationService service;

    /** 정격 1000W·8코어 노드. 유휴 비율 0.3이라 유휴는 300W다. 전력 0은 "수집 실패"를 뜻한다. */
    private static NodeResource node(String mdcId, String nodeId, double watts) {
        return new NodeResource(mdcId, nodeId, 8, 16384, 0, 30.0, 30.0, 0, watts, 1000, 40.0, 0, 0);
    }

    private static Mdc find(PowerOptimizationResponse r, String mdcId) {
        return r.mdcs().stream().filter(m -> m.mdcId().equals(mdcId)).findFirst().orElseThrow();
    }

    @DisplayName("예산이 없어도 MDC 별 현황은 나오고 예산 칸만 비어 있다")
    @Test
    void summary_without_budget() {
        PowerOptimizationResponse r = service.diagnose(List.of(
                node("seoul", "s1", 400), node("seoul", "s2", 300), node("busan", "b1", 200)));

        assertEquals(2, r.mdcs().size());
        assertEquals(List.of("busan", "seoul"), r.mdcs().stream().map(Mdc::mdcId).toList(),
                "MDC 식별자 오름차순이라 응답이 결정적이다");

        Mdc seoul = find(r, "seoul");
        assertEquals(2, seoul.nodeCount());
        assertEquals(2, seoul.measuredNodes());
        assertEquals(700.0, seoul.totalPowerWatts());
        assertEquals(700.0, seoul.effectivePowerWatts(), "예약이 없으면 실효는 실측과 같다");
        assertNull(seoul.budgetWatts());
        assertNull(seoul.remainingWatts());
        assertFalse(seoul.overBudget());
    }

    @DisplayName("예산을 등록하면 남은 여유가 나오고 넘기면 초과로 표시된다")
    @Test
    void summary_with_budget_and_over_budget() {
        MdcPowerBudgets budgets = MdcPowerBudgets.of(List.of(
                new MdcPowerBudget("seoul", 1000), new MdcPowerBudget("busan", 100)));
        PowerOptimizationResponse r = service.diagnose(
                List.of(node("seoul", "s1", 400), node("seoul", "s2", 300), node("busan", "b1", 200)),
                List.of(), budgets);

        Mdc seoul = find(r, "seoul");
        assertEquals(1000.0, seoul.budgetWatts());
        assertEquals(300.0, seoul.remainingWatts());
        assertFalse(seoul.overBudget());

        Mdc busan = find(r, "busan");
        assertEquals(-100.0, busan.remainingWatts(), "이미 넘긴 MDC 는 남은 여유가 음수다");
        assertTrue(busan.overBudget());
    }

    @DisplayName("미계측 노드는 합계에서 빠지고 대수 차이로 과소 평가가 드러난다")
    @Test
    void unmeasured_nodes_are_visible_as_count_gap() {
        PowerOptimizationResponse r = service.diagnose(List.of(
                node("seoul", "s1", 400), node("seoul", "blind", 0)));

        Mdc seoul = find(r, "seoul");
        assertEquals(2, seoul.nodeCount());
        assertEquals(1, seoul.measuredNodes(), "계측 대수가 노드 수보다 적으면 합계가 과소 평가다");
        assertEquals(400.0, seoul.totalPowerWatts());
        assertEquals(400.0, seoul.effectivePowerWatts());
    }

    @DisplayName("기존 응답 필드는 그대로다 — MDC 배열만 늘었다")
    @Test
    void existing_fields_unchanged() {
        PowerOptimizationResponse r = service.diagnose(List.of(node("seoul", "s1", 400)));

        assertEquals(1, r.cluster().totalNodes());
        assertEquals(400.0, r.cluster().totalPowerWatts());
        assertTrue(r.cluster().measured());
        assertEquals(1, r.mdcs().size());
    }

    // ── 기준선 일치 ────────────────────────────────────────────

    @DisplayName("클러스터 실측 합계는 MDC 별 실측 합계의 합과 같다")
    @Test
    void cluster_total_equals_sum_of_mdc_totals() {
        PowerOptimizationResponse r = service.diagnose(List.of(
                node("seoul", "s1", 400), node("seoul", "s2", 300),
                node("busan", "b1", 200), node("busan", "blind", 0)));

        double sumOfMdcs = r.mdcs().stream().mapToDouble(Mdc::totalPowerWatts).sum();
        assertEquals(r.cluster().totalPowerWatts(), sumOfMdcs,
                "미계측 노드는 양쪽 모두 0 이라 합이 어긋나면 안 된다");
    }

    @DisplayName("예약이 쌓이면 실효 합이 실측 합보다 크고, 남은 여유는 실효를 기준으로 준다")
    @Test
    void reservations_raise_effective_sum() {
        NodeResource n = node("seoul", "s1", 400);
        n.reserve(2, 1024, 0);   // 8코어 중 2코어 → (1000−300)×0.25 = 175W

        Mdc seoul = find(service.diagnose(List.of(n), List.of(),
                MdcPowerBudgets.of(List.of(new MdcPowerBudget("seoul", 1000)))), "seoul");

        assertEquals(400.0, seoul.totalPowerWatts(), "실측은 그대로다");
        assertEquals(575.0, seoul.effectivePowerWatts(), "예약분 175W 가 실효에 더해진다");
        assertEquals(425.0, seoul.remainingWatts(), "남은 여유는 실효 기준 — 실측 기준이면 600 이 나온다");
    }

    /**
     * 진단의 남은 여유와 배치 판정(C6)이 같은 기준선을 쓰는지 확인한다.
     *
     * <p>남은 여유를 실측으로 내면 예약이 쌓인 MDC에서 <b>"여유 100W 남았다"고 보여 주고도
     * 실제 배치는 C6에 막히는</b> 상황이 생긴다. 그래서 두 경로를 나란히 확인한다.</p>
     */
    @DisplayName("진단이 초과라고 하면 배치도 막히고, 여유가 있다고 하면 배치도 통과한다")
    @Test
    void diagnosis_and_constraint_agree() {
        MdcPowerBudgets budgets = MdcPowerBudgets.of(List.of(new MdcPowerBudget("seoul", 500)));
        Workload workload = new Workload("w", 1, 1024);

        NodeResource forDiagnosis = node("seoul", "s1", 400);
        forDiagnosis.reserve(2, 1024, 0);
        Mdc seoul = find(service.diagnose(List.of(forDiagnosis), List.of(), budgets), "seoul");

        // 실측 400 < 예산 500이라 옛 계산이면 "여유 100W"였다. 실효는 575라 이미 초과다.
        assertTrue(seoul.overBudget(), "실효 575W 는 예산 500W 를 넘는다");
        assertTrue(seoul.remainingWatts() < 0);

        NodeResource forPlacement = node("seoul", "s1", 400);
        forPlacement.reserve(2, 1024, 0);
        PlacementContext ctx = PlacementContext.of(List.of(forPlacement), new SchedulingPolicy("p", "d"))
                .withMdcBudgets(budgets);
        String reason = ConstraintEvaluator.withDefaults().exclusionReason(workload, forPlacement, ctx);

        assertTrue(reason != null && reason.startsWith("MDC seoul 전력 합계 예상"),
                "진단이 초과라고 했으면 배치도 막혀야 한다: " + reason);
    }
}

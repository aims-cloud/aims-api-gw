package com.innogrid.aims.scheduler.engine.constraint;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.MdcPowerBudget;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.model.NodeResource;

/**
 * C6 MDC 전력 예산 — MDC 한 곳의 합계 상한.
 *
 * <p>노드는 전부 정격 1000W·8코어이고 유휴 비율 0.3 이라 유휴는 300W 다. 실측 400W 인 노드에 2코어를 얹으면
 * 한계 전력이 {@code (1000−300)×2/8 = 175W} 라 배치 후 예상은 575W — 노드 한 대 기준(C4)으로는 여유가 있다.
 * MDC 합계로 봐야 걸리는 상황을 만든다.</p>
 */
class ConstraintEvaluatorMdcBudgetTest {

    private final ConstraintEvaluator evaluator = ConstraintEvaluator.withDefaults();

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** 정격 1000W·8코어·CPU 20%·메모리 20% 노드. 전력 0 은 "수집 실패"를 뜻한다. */
    private static NodeResource node(String mdcId, String nodeId, double watts) {
        return new NodeResource(mdcId, nodeId, 8, 16384, 0, 20.0, 20.0, 0,
                watts, 1000, 40.0, 0, 0);
    }

    private static Workload workload(int cpu) {
        return new Workload("w-" + cpu, cpu, 1024);
    }

    private static SchedulingPolicy policy() {
        return new SchedulingPolicy("p", "테스트 정책");
    }

    private static PlacementContext ctx(List<NodeResource> nodes, MdcPowerBudgets budgets) {
        return PlacementContext.of(nodes, policy()).withMdcBudgets(budgets);
    }

    private static MdcPowerBudgets budget(String mdcId, double watts) {
        return MdcPowerBudgets.of(List.of(new MdcPowerBudget(mdcId, watts)));
    }

    // ── 판정 ───────────────────────────────────────────────────

    @DisplayName("예산 원장이 비어 있으면 아무 노드도 막지 않는다 — 이 기능 이전과 같은 동작")
    @Test
    void empty_ledger_blocks_nothing() {
        NodeResource a = node("seoul", "a", 400);
        NodeResource b = node("seoul", "b", 400);
        assertNull(evaluator.exclusionReason(workload(2), a, ctx(List.of(a, b), MdcPowerBudgets.EMPTY)));
    }

    @DisplayName("예산이 등록되지 않은 MDC 는 건너뛴다")
    @Test
    void unregistered_mdc_is_skipped() {
        NodeResource a = node("busan", "a", 400);
        NodeResource b = node("busan", "b", 400);
        assertNull(evaluator.exclusionReason(workload(2), a, ctx(List.of(a, b), budget("seoul", 100))));
    }

    @DisplayName("전력을 수집하지 못한 후보는 건너뛴다 — 모르는 값으로 배치를 막지 않는다")
    @Test
    void unmeasured_candidate_is_skipped() {
        NodeResource a = node("seoul", "a", 0);
        NodeResource b = node("seoul", "b", 400);
        assertNull(evaluator.exclusionReason(workload(2), a, ctx(List.of(a, b), budget("seoul", 100))));
    }

    @DisplayName("MDC 합계가 예산 안이면 통과한다")
    @Test
    void sum_within_budget_passes() {
        NodeResource a = node("seoul", "a", 400);
        NodeResource b = node("seoul", "b", 400);
        // 575 + 400 = 975 <= 1000
        assertNull(evaluator.exclusionReason(workload(2), a, ctx(List.of(a, b), budget("seoul", 1000))));
    }

    @DisplayName("MDC 합계가 예산을 넘으면 제외하고 사유에 합계·예산·계측 대수를 적는다")
    @Test
    void sum_over_budget_excludes_with_reason() {
        NodeResource a = node("seoul", "a", 400);
        NodeResource b = node("seoul", "b", 400);
        String reason = evaluator.exclusionReason(workload(2), a, ctx(List.of(a, b), budget("seoul", 900)));

        assertNotNull(reason);
        assertTrue(reason.startsWith("MDC seoul 전력 합계 예상"), reason);
        assertTrue(reason.contains("975W"), reason);
        assertTrue(reason.contains("예산 900W"), reason);
        assertTrue(reason.contains("계측 2대"), reason);
    }

    @DisplayName("다른 MDC 의 노드는 합계에 들어가지 않는다")
    @Test
    void other_mdc_nodes_are_not_summed() {
        NodeResource a = node("seoul", "a", 400);
        NodeResource far = node("busan", "far", 400);
        assertNull(evaluator.exclusionReason(workload(2), a, ctx(List.of(a, far), budget("seoul", 600))));
    }

    @DisplayName("미계측 형제는 합계에서 빼고 사유에 대수를 적는다 — 합계가 과소 평가임을 드러낸다")
    @Test
    void unmeasured_siblings_are_excluded_and_counted() {
        NodeResource a = node("seoul", "a", 400);
        NodeResource b = node("seoul", "b", 400);
        NodeResource blind = node("seoul", "blind", 0);
        String reason = evaluator.exclusionReason(workload(2), a, ctx(List.of(a, b, blind), budget("seoul", 900)));

        assertNotNull(reason);
        assertTrue(reason.contains("계측 2대, 미계측 1대 제외"), reason);
    }

    @DisplayName("연속 배치로 합계가 누적되어 예산에 걸린다 — 실측만 더했다면 걸리지 않는다")
    @Test
    void reservations_accumulate_into_mdc_sum() {
        NodeResource a = node("seoul", "a", 400);
        NodeResource b = node("seoul", "b", 400);
        PlacementContext ctx = ctx(List.of(a, b), budget("seoul", 1100));

        // 첫 배치: 575 + 400 = 975 <= 1100 통과
        assertNull(evaluator.exclusionReason(workload(2), a, ctx));
        ctx.reserve("a", workload(2));

        // 두 번째 배치: a 의 실효 전력이 예약분만큼 올라 합계가 예산을 넘는다.
        // 실측(400+400)만 더했다면 여기서도 975 로 계산돼 그냥 통과했을 것이다.
        String reason = evaluator.exclusionReason(workload(2), b, ctx);
        assertNotNull(reason, "예약분이 합계에 반영되지 않으면 예산이 누적되지 않는다");
        assertTrue(reason.startsWith("MDC seoul 전력 합계 예상"), reason);
    }
}

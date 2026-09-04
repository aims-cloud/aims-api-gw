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
import com.innogrid.aims.scheduler.entity.MdcPowerBudget;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.model.NodeResource;

/** 전력 최적화 진단의 MDC 별 요약 — 예산이 없어도 현황은 나오고, 있으면 남은 여유·초과가 함께 나온다. */
@SpringBootTest
class PowerOptimizationMdcTest {

    @Autowired PowerOptimizationService service;

    /** 정격 1000W·8코어 노드. 전력 0 은 "수집 실패"를 뜻한다. */
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
}

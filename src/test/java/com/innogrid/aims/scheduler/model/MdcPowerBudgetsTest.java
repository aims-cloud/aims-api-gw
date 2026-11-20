package com.innogrid.aims.scheduler.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.MdcPowerBudget;

/** MDC 전력 예산 스냅샷 — 배치 1회당 한 번 읽어 후보마다 DB를 치지 않게 하는 조회 구조. */
class MdcPowerBudgetsTest {

    @DisplayName("원장이 비면 빈 스냅샷이고 어떤 MDC 도 예산이 없다")
    @Test
    void empty_ledger_yields_no_budget() {
        assertTrue(MdcPowerBudgets.of(List.of()).isEmpty());
        assertTrue(MdcPowerBudgets.of(null).isEmpty());
        assertTrue(MdcPowerBudgets.EMPTY.budgetOf("seoul").isEmpty());
    }

    @DisplayName("등록된 MDC 는 예산을 돌려주고 등록되지 않은 MDC 는 비어 있다")
    @Test
    void registered_mdc_returns_budget() {
        MdcPowerBudgets budgets = MdcPowerBudgets.of(List.of(
                new MdcPowerBudget("seoul", 4000), new MdcPowerBudget("busan", 2500)));

        assertEquals(2, budgets.size());
        assertFalse(budgets.isEmpty());
        assertEquals(4000, budgets.budgetOf("seoul").orElseThrow());
        assertEquals(2500, budgets.budgetOf("busan").orElseThrow());
        assertTrue(budgets.budgetOf("daejeon").isEmpty());
    }

    @DisplayName("MDC 식별자가 null 이면 예산이 없다 — 라벨 없는 노드로 판정이 깨지지 않는다")
    @Test
    void null_mdc_id_has_no_budget() {
        assertTrue(MdcPowerBudgets.of(List.of(new MdcPowerBudget("seoul", 4000))).budgetOf(null).isEmpty());
    }
}

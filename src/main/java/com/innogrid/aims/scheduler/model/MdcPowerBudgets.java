package com.innogrid.aims.scheduler.model;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

import com.innogrid.aims.scheduler.entity.MdcPowerBudget;

/**
 * MDC 전력 예산 원장의 스냅샷 — 배치 <b>1회당 한 번만</b> 읽어 후보마다 DB를 치지 않게 한다.
 * 노드 전력 사양의 {@link NodePowerSpecs}, MDC 링크의 {@link LinkMatrix}와 같은 방식이다.
 *
 * <p>예산이 등록되지 않은 MDC는 비어 있는 값을 돌려주고, 제약은 그 노드를 그냥 통과시킨다.
 * 그래서 원장이 비어 있으면 배치 동작이 이 기능 이전과 완전히 같다.</p>
 */
public record MdcPowerBudgets(Map<String, Double> budgetByMdc) {

    public static final MdcPowerBudgets EMPTY = new MdcPowerBudgets(Map.of());

    public MdcPowerBudgets {
        budgetByMdc = budgetByMdc == null ? Map.of() : Map.copyOf(budgetByMdc);
    }

    /** 같은 MDC가 둘 이상이면(유니크 제약이 막지만) 뒤엣것을 쓴다. */
    public static MdcPowerBudgets of(List<MdcPowerBudget> budgets) {
        if (budgets == null || budgets.isEmpty()) return EMPTY;
        return new MdcPowerBudgets(budgets.stream().collect(Collectors.toMap(
                MdcPowerBudget::getMdcId, MdcPowerBudget::getBudgetWatts, (a, b) -> b)));
    }

    /** 이 MDC의 예산(W). 등록되지 않았으면 비어 있다. */
    public OptionalDouble budgetOf(String mdcId) {
        Double v = mdcId == null ? null : budgetByMdc.get(mdcId);
        return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
    }

    public int size() { return budgetByMdc.size(); }
    public boolean isEmpty() { return budgetByMdc.isEmpty(); }
}

package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.MdcPowerBudget;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 예산 원장이 비었을 때 <b>이 기능 이전과 완전히 같은가</b>, 그리고 통합 진단의 샌드박스가 예산을 그대로 잇는가.
 *
 * <p>샌드박스가 예산을 빠뜨리면 시뮬레이션은 "옮길 수 있다"고 답하고 실제 배치는 C6 으로 막히는 어긋남이
 * 생긴다 — 링크에서 한 번 겪은 것과 같은 함정이다.</p>
 */
class MdcBudgetSandboxAndDefaultsTest {

    private final SchedulerProperties properties = new SchedulerProperties();
    private final ConsolidationService service = new ConsolidationService(
            mock(SchedulingService.class), mock(WorkloadRepository.class),
            new EngineRegistry(List.of(new PowerAwareScheduler())),
            new PowerModel(properties), properties);

    private static NodeResource node(String id, double watts) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, 20, 20, 0, watts, 1000, 40, 0, 0);
    }

    private static SchedulingPolicy policy() {
        return new SchedulingPolicy("p", "d");
    }

    // ── 기본값 ─────────────────────────────────────────────────

    @DisplayName("배치 스냅샷의 기본 예산은 비어 있다 — 어느 조립 경로로 만들어도")
    @Test
    void default_budgets_are_empty() {
        List<NodeResource> nodes = List.of(node("n1", 400));

        assertTrue(PlacementContext.of(nodes, policy()).mdcBudgets().isEmpty());
        assertTrue(PlacementContext.of(nodes, policy(), ConstraintEvaluator.withDefaults()).mdcBudgets().isEmpty());
        assertSame(MdcPowerBudgets.EMPTY, PlacementContext.of(nodes, policy()).withMdcBudgets(null).mdcBudgets(),
                "null 을 실으면 빈 원장 — 판정이 깨지지 않는다");
    }

    @DisplayName("원장이 비면 제약 사유에 MDC 예산이 나오지 않는다")
    @Test
    void empty_ledger_never_reports_mdc_budget() {
        NodeResource a = node("a", 400), b = node("b", 400);
        PlacementContext ctx = PlacementContext.of(List.of(a, b), policy());
        Workload w = new Workload("w", 2, 1024);

        assertNull(ctx.exclusionReason(w, a));
        ctx.reserve("a", w);
        assertNull(ctx.exclusionReason(w, b), "예약이 쌓여도 원장이 비면 MDC 로 막지 않는다");
    }

    // ── 샌드박스 ───────────────────────────────────────────────

    @DisplayName("샌드박스는 기준 컨텍스트의 예산 스냅샷을 그대로 잇는다")
    @Test
    void sandbox_inherits_mdc_budgets() {
        NodeResource n1 = node("n1", 400), n2 = node("n2", 400);
        MdcPowerBudgets budgets = MdcPowerBudgets.of(List.of(new MdcPowerBudget("mdc-01", 4000)));
        PlacementContext base = PlacementContext.of(List.of(n1, n2), policy()).withMdcBudgets(budgets);

        PlacementContext sandbox = service.sandboxWithout(n1, base.nodes(), policy(), base.linkMatrix(), base.mdcBudgets());

        assertSame(budgets, sandbox.mdcBudgets(), "실제 배치와 같은 예산 스냅샷");
        assertEquals(List.of("n2"), sandbox.nodes().stream().map(NodeResource::getNodeId).toList());
    }

    @DisplayName("예산을 넘기지 않는 옛 호출 형태는 빈 원장이라 제약이 걸리지 않는다")
    @Test
    void legacy_sandbox_call_has_no_budget() {
        NodeResource n1 = node("n1", 400);
        PlacementContext sandbox = service.sandboxWithout(n1, List.of(n1, node("n2", 400)), policy(), LinkMatrix.EMPTY);

        assertSame(MdcPowerBudgets.EMPTY, sandbox.mdcBudgets());
    }
}

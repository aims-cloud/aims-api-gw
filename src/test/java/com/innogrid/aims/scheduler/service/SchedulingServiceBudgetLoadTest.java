package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.MdcPowerBudget;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 배치 컨텍스트가 MDC 전력 예산 원장을 <b>실행당 1회</b> 읽는가, 그리고 원장을 붙이지 않으면 꺼지는가.
 *
 * <p>링크 원장과 달리 정책 게이트가 없다 — 운영자가 원장에 직접 넣은 값이라 정책 설정과 무관하게 적용한다
 * (워크로드·스케줄 전력 예산과 같은 관례). 대신 <b>원장이 비면 스냅샷도 비어</b> C6 이 아무 노드도 막지 않는다.</p>
 *
 * <p>조립은 {@link SchedulingServiceBuilder} 가 한다 — 선택 원장을 붙이지 않으면 그 기능이 꺼진다는
 * 빌더의 계약을 여기서 고정한다.</p>
 */
class SchedulingServiceBudgetLoadTest {

    private final NodeResourceCollector collector = mock(NodeResourceCollector.class);
    private final MdcPowerBudgetRepository budgetRepository = mock(MdcPowerBudgetRepository.class);
    private final SchedulerProperties properties = new SchedulerProperties();

    private PlacementContext contextOf(SchedulingService service) {
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(List.of(node()), List.of()));
        return service.createContext(new SchedulingPolicy("p", "d"));
    }

    private static NodeResource node() {
        return new NodeResource("mdc-01", "n1", 8, 16384, 0, 20, 20, 0, 400, 1000, 40, 0, 0);
    }

    @DisplayName("예산 원장을 붙이지 않으면 스냅샷이 비어 있다 — 이 기능 이전과 같은 동작")
    @Test
    void without_ledger_budgets_are_empty() {
        SchedulingService service = SchedulingServiceBuilder.aSchedulingService()
                .collector(collector).properties(properties).build();   // 예산 원장을 붙이지 않는다

        assertSame(MdcPowerBudgets.EMPTY, contextOf(service).mdcBudgets());
    }

    @DisplayName("원장을 붙이면 실행당 1회만 읽어 컨텍스트에 싣는다")
    @Test
    void ledger_is_read_once_per_run() {
        when(budgetRepository.findAll()).thenReturn(List.of(new MdcPowerBudget("mdc-01", 4000)));
        SchedulingService service = SchedulingServiceBuilder.aSchedulingService()
                .collector(collector).properties(properties).budgets(budgetRepository).build();

        PlacementContext ctx = contextOf(service);

        verify(budgetRepository, times(1)).findAll();
        assertEquals(4000.0, ctx.mdcBudgets().budgetOf("mdc-01").orElseThrow());
        assertTrue(ctx.mdcBudgets().budgetOf("mdc-02").isEmpty());
    }

    @DisplayName("원장이 비어 있으면 조회는 하되 스냅샷은 빈 값이다")
    @Test
    void empty_ledger_yields_empty_snapshot() {
        when(budgetRepository.findAll()).thenReturn(List.of());
        SchedulingService service = SchedulingServiceBuilder.aSchedulingService()
                .collector(collector).properties(properties).budgets(budgetRepository).build();

        assertTrue(contextOf(service).mdcBudgets().isEmpty());
    }
}

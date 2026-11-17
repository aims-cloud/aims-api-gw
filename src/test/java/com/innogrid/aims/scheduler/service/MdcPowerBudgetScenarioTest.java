package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.innogrid.aims.scheduler.dto.MdcPowerBudgetRequest;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse.Mdc;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.MdcPowerBudget;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.MdcPowerBudgetRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 시나리오 — MDC 한 곳에 예산을 걸면 그 MDC 전체가 후보에서 빠지는가.
 *
 * <pre>
 * 배경   가상 클러스터는 MDC 3곳(mdc-seoul 3대·mdc-busan 3대·mdc-daejeon 2대)이고, 전력을 수집하는 노드는 seoul 3대와 busan 2대다.
 *        노드 한 대의 정격(C4)만 보면 각자 여유가 있어도 MDC 합계로는 넘을 수 있다 — 그 상황을 만든다.
 * 절차   ① 예산이 없을 때 mdc-seoul 노드가 후보에 있다  ② 현재 합계의 절반으로 예산을 건다
 *        ③ mdc-seoul 3대가 전부 "MDC 예산 초과"로 빠지고 배치는 다른 MDC 로 간다  ④ 진단에 초과가 표시된다
 *        ⑤ 예산을 지우면 원복된다
 * </pre>
 *
 * <p>가상 클러스터는 시각에 따라 값이 변하므로 <b>절대값을 확인하지 않는다.</b> 현재 합계를 읽어 그 절반을
 * 예산으로 걸어, 주기 어디에서 돌아도 초과가 되게 한다(진폭이 합계의 절반을 넘지 않는다).</p>
 */
@SpringBootTest
@TestPropertySource(properties = "aims.scheduler.mock-enabled=true")
class MdcPowerBudgetScenarioTest {

    private static final String MDC = "mdc-seoul";

    /** 언제나 다른 제약에 걸리지 않는 평범한 노드 — 제외 사유가 MDC 예산인지 여기서 확인한다. */
    private static final String PLAIN_NODE = "seoul-01:9100";

    @Autowired MdcPowerBudgetService budgetService;
    @Autowired MdcPowerBudgetRepository budgetRepository;
    @Autowired SchedulingService schedulingService;
    @Autowired PowerOptimizationService optimizationService;

    @AfterEach
    void clean() {
        budgetRepository.findAll().stream().filter(b -> b.getMdcId().equals(MDC)).forEach(budgetRepository::delete);
    }

    private static Workload workload() {
        return new Workload("mpb-scenario", 1, 1024);
    }

    private List<NodeResource> nodesOf(PlacementContext ctx, String mdcId) {
        return ctx.nodes().stream().filter(n -> mdcId.equals(n.getMdcId())).toList();
    }

    /** 이 MDC 노드 중 제외되지 않은 것. */
    private List<String> eligible(PlacementContext ctx, String mdcId) {
        return nodesOf(ctx, mdcId).stream()
                .filter(n -> ctx.exclusionReason(workload(), n) == null)
                .map(NodeResource::getNodeId).toList();
    }

    @DisplayName("MDC 예산을 현재 합계 아래로 걸면 그 MDC 노드가 전부 빠지고, 지우면 돌아온다")
    @Test
    void budget_excludes_whole_mdc_and_delete_restores() {
        PlacementContext before = schedulingService.createContext(schedulingService.resolvePolicy(null));
        assertEquals(3, nodesOf(before, MDC).size(), "가상 클러스터의 mdc-seoul 은 3대다");
        assertFalse(eligible(before, MDC).isEmpty(), "예산이 없으면 mdc-seoul 노드가 후보에 있다");

        double sum = nodesOf(before, MDC).stream()
                .filter(n -> n.getPowerWatts() > 0).mapToDouble(NodeResource::getPowerWatts).sum();
        assertTrue(sum > 0, "mdc-seoul 3대는 모두 전력을 수집한다");

        var saved = budgetService.create(new MdcPowerBudgetRequest(MDC, sum / 2, "SPEC_SHEET", null));

        PlacementContext after = schedulingService.createContext(schedulingService.resolvePolicy(null));
        assertTrue(eligible(after, MDC).isEmpty(),
                "합계의 절반이 예산이면 mdc-seoul 노드는 전부 빠진다: " + eligible(after, MDC));

        // seoul-03은 뜨거운 노드라 시각에 따라 온도(C3)에 먼저 걸릴 수 있다. 사유가 MDC 예산인지는
        // 다른 제약에 걸리지 않는 평범한 노드로 확인한다.
        NodeResource plain = nodesOf(after, MDC).stream()
                .filter(n -> n.getNodeId().equals(PLAIN_NODE)).findFirst().orElseThrow();
        assertTrue(after.exclusionReason(workload(), plain).startsWith("MDC " + MDC + " 전력 합계 예상"),
                "제외 사유가 MDC 예산이어야 한다: " + after.exclusionReason(workload(), plain));

        assertFalse(eligible(after, "mdc-busan").isEmpty() && eligible(after, "mdc-daejeon").isEmpty(),
                "다른 MDC 는 영향을 받지 않아 배치할 곳이 남는다");

        budgetService.delete(saved.getId());
        PlacementContext restored = schedulingService.createContext(schedulingService.resolvePolicy(null));
        assertFalse(eligible(restored, MDC).isEmpty(), "예산을 지우면 mdc-seoul 노드가 후보로 돌아온다");
    }

    @DisplayName("진단의 MDC 요약은 예산이 없으면 현황만, 넘기면 초과를 표시한다")
    @Test
    void diagnosis_shows_mdc_summary_and_over_budget() {
        PowerOptimizationResponse plain = optimizationService.diagnoseCurrent();
        assertEquals(3, plain.mdcs().size(), "가상 클러스터의 MDC 는 3곳이다");

        Mdc seoulPlain = plain.mdcs().stream().filter(m -> m.mdcId().equals(MDC)).findFirst().orElseThrow();
        assertEquals(3, seoulPlain.nodeCount());
        assertEquals(3, seoulPlain.measuredNodes(), "mdc-seoul 3대는 모두 전력을 수집한다");
        assertNull(seoulPlain.budgetWatts(), "예산을 등록하지 않으면 예산 칸이 비어 있다");
        assertFalse(seoulPlain.overBudget());

        budgetService.create(new MdcPowerBudgetRequest(MDC, seoulPlain.totalPowerWatts() / 2, null, null));

        Mdc seoulOver = optimizationService.diagnoseCurrent().mdcs().stream()
                .filter(m -> m.mdcId().equals(MDC)).findFirst().orElseThrow();
        assertNotNull(seoulOver.budgetWatts());
        assertTrue(seoulOver.overBudget(), "합계의 절반이 예산이면 초과다");
        assertTrue(seoulOver.remainingWatts() < 0, "남은 여유가 음수로 나온다");
    }

    @DisplayName("전력을 수집하지 못하는 MDC 는 예산을 걸어도 막히지 않는다 — 모르는 값으로 배치를 막지 않는다")
    @Test
    void unmeasured_mdc_is_not_blocked_by_budget() {
        // daejeon 2대는 IPMI가 없어 전력이 0이다. 예산을 아주 낮게 걸어도 C6은 후보를 건드리지 않는다.
        budgetRepository.save(new MdcPowerBudget("mdc-daejeon", 1.0));
        try {
            PlacementContext ctx = schedulingService.createContext(schedulingService.resolvePolicy(null));
            List<NodeResource> daejeon = nodesOf(ctx, "mdc-daejeon");
            assertFalse(daejeon.isEmpty(), "mdc-daejeon 노드가 후보 목록에 있다");
            assertTrue(daejeon.stream().allMatch(n -> n.getPowerWatts() <= 0), "mdc-daejeon 은 전력 미계측이다");
            assertEquals(daejeon.size(), eligible(ctx, "mdc-daejeon").size(),
                    "미계측 노드는 예산 1W 로도 빠지지 않는다");
        } finally {
            budgetRepository.findAll().stream().filter(b -> b.getMdcId().equals("mdc-daejeon")).forEach(budgetRepository::delete);
        }
    }
}

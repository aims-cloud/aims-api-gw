package com.innogrid.aims.scheduler.engine.constraint;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.MdcPowerBudget;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 제외 사유 <b>문장</b>의 현행 고정 — 사유를 코드로 나누는 변경이 문구를 건드리지 않았음을 증명한다.
 *
 * <p>사유 문자열은 화면에 그대로 나가고, 판 응답의 제외 사유 분포는 이 문장을 <b>맵 키</b>로 쓴다. 그래서
 * 코드를 더하는 작업에서 문장이 한 글자라도 바뀌면 화면 문구와 판 집계가 함께 움직인다. 이 테스트는 사유
 * 까닭 14종 가운데 12종의 문장을 지금 값 그대로 못 박아 그 일이 없었음을 보인다. 11종은 문장 전체를,
 * MDC 전력 합계는 수치가 노드 상태에 따라 달라져 앞뒤만 본다. 냉각 대기와 배치 후 예상 전력은 이 테스트
 * 밖이며, 두 문장은 제약 테스트가 따로 확인한다.</p>
 */
class ConstraintReasonWordingTest {

    private final ConstraintEvaluator evaluator = ConstraintEvaluator.withDefaults();

    @DisplayName("사유 문장 12종이 지금 문구 그대로다")
    @Test
    void reason_wording_is_pinned() {
        assertEquals("자원 부족 (필요 CPU:99 MEM:512MB GPU:0, 가용 CPU:8.0 MEM:16384MB GPU:0)",
                reason(workload(99, 512), idleNode()));

        Workload disk = workload(1, 512);
        disk.setRequiredDiskGb(5000);
        assertEquals("디스크 부족 (필요 5000GB, 가용 1700GB / 총량 2000GB)", reason(disk, idleNode().withDiskCapacityGb(2000)));

        assertEquals("CPU 사용률 초과 (95.0% > 80.0%)", reason(workload(1, 512), busyNode(95, 10)));
        assertEquals("메모리 사용률 초과 (95.0% > 85.0%)", reason(workload(1, 512), busyNode(10, 95)));

        // 온도 초과는 그 노드를 냉각 대기로 표시하므로, 뒤이은 사례는 다른 노드 id를 쓴다
        assertEquals("온도 초과 (90.0°C > 75.0°C)", reason(workload(1, 512), hotNode("n-hot", 90), thermalPolicy()));
        assertEquals("배치 후 예상 온도 75.3°C > 임계치 75.0°C",
                reason(workload(32, 512), hotNode("n-predict", 70), thermalPolicy()));

        Workload tempLimited = workload(1, 512);
        tempLimited.setMaxTemperatureCelsius(50.0);
        assertEquals("워크로드 온도 제약 초과 (70.0°C > 50.0°C)", reason(tempLimited, hotNode("n-wl-temp", 70)));

        Workload powerLimited = workload(1, 512);
        powerLimited.setMaxPowerBudgetWatts(100.0);
        assertEquals("워크로드 전력 제약 초과 (400.0W > 100.0W)", reason(powerLimited, idleNode()));

        assertEquals("전력 여유 부족 (여유 0.0%)", reason(workload(1, 512), fullPowerNode(), powerPolicy()));

        PlacementContext budgeted = PlacementContext.of(List.of(idleNode()), new SchedulingPolicy("p", "d"),
                        ConstraintEvaluator.withDefaults())
                .withPowerBudget(100.0);
        assertEquals("스케줄 전력 예산 초과 (400.0W > 100.0W)",
                evaluator.exclusionReason(workload(1, 512), idleNode(), budgeted));

        assertTrue(mdcBudgetReason().startsWith("MDC mdc-a 전력 합계 예상 "), mdcBudgetReason());
        assertTrue(mdcBudgetReason().endsWith("W > 예산 100W (계측 1대)"), mdcBudgetReason());

        assertEquals("MDC 링크 단절 (mdc-a ↔ mdc-b DOWN)", linkDownReason());
    }

    @DisplayName("제약을 통과하는 후보는 사유가 없다")
    @Test
    void eligible_candidate_has_no_reason() {
        assertNull(reason(workload(1, 512), idleNode()));
    }

    @DisplayName("사유는 먼저 걸린 제약 하나만 돌려준다")
    @Test
    void only_the_first_matching_constraint_is_reported() {
        // 자원도 모자라고 사용률도 넘는 노드 — C1이 먼저라 자원 사유가 나온다
        NodeResource node = busyNode(95, 10);
        String r = reason(workload(999, 512), node);
        assertNotNull(r);
        assertTrue(r.startsWith("자원 부족"), r);
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private String reason(Workload w, NodeResource node) {
        return reason(w, node, new SchedulingPolicy("p", "d"));
    }

    private String reason(Workload w, NodeResource node, SchedulingPolicy policy) {
        return evaluator.exclusionReason(w, node,
                PlacementContext.of(List.of(node), policy, ConstraintEvaluator.withDefaults()));
    }

    private String mdcBudgetReason() {
        NodeResource node = new NodeResource("mdc-a", "n-a", 8, 16384, 0, 10, 10, 15, 300, 400, 40, 0, 0);
        MdcPowerBudget budget = new MdcPowerBudget("mdc-a", 100.0);
        PlacementContext ctx = PlacementContext.of(List.of(node), new SchedulingPolicy("p", "d"),
                        ConstraintEvaluator.withDefaults())
                .withMdcBudgets(MdcPowerBudgets.of(List.of(budget)));
        return evaluator.exclusionReason(workload(1, 512), node, ctx);
    }

    private String linkDownReason() {
        NodeResource node = new NodeResource("mdc-b", "n-b", 8, 16384, 0, 10, 10, 15, 0, 0, 0, 0, 0);
        SchedulingPolicy policy = new SchedulingPolicy("p", "d");
        policy.setAffinityEnabled(true);
        Workload w = workload(1, 512);
        w.setDataMdcId("mdc-a");
        MdcLink down = new MdcLink("mdc-a", "mdc-b", 5);
        down.setStatus(MdcLink.LinkStatus.DOWN);
        PlacementContext ctx = PlacementContext.of(List.of(node), policy, ConstraintEvaluator.withDefaults())
                .withLinkMatrix(LinkMatrix.of(List.of(down)));
        return evaluator.exclusionReason(w, node, ctx);
    }

    private static Workload workload(int cpu, int memoryMb) {
        return new Workload("w", cpu, memoryMb);
    }

    /** 여유로운 노드 — 8코어·16GB 전량 가용·디스크 2000GB(사용 15%)·400W/1000W·40℃. */
    private static NodeResource idleNode() {
        return new NodeResource("mdc-a", "n-a", 8, 16384, 0, 0, 0, 15, 400, 1000, 40, 0, 0);
    }

    /** 사용률만 높고 절대 여유는 남는 큰 노드 — 자원 제약(C1)이 먼저 걸리지 않게 한다. */
    private static NodeResource busyNode(double cpu, double memory) {
        return new NodeResource("mdc-a", "n-a", 64, 65536, 0, cpu, memory, 15, 400, 1000, 40, 0, 0);
    }

    private static NodeResource hotNode(String nodeId, double celsius) {
        return new NodeResource("mdc-a", nodeId, 64, 65536, 0, 10, 10, 15, 400, 1000, celsius, 0, 0);
    }

    /** 전력 여유가 0인 노드 — 실측이 정격과 같다. */
    private static NodeResource fullPowerNode() {
        return new NodeResource("mdc-a", "n-a", 8, 16384, 0, 10, 10, 15, 1000, 1000, 40, 0, 0);
    }

    private static SchedulingPolicy thermalPolicy() {
        SchedulingPolicy p = new SchedulingPolicy("p", "d");
        p.setTemperatureAwareEnabled(true);
        return p;
    }

    private static SchedulingPolicy powerPolicy() {
        SchedulingPolicy p = new SchedulingPolicy("p", "d");
        p.setPowerAwareEnabled(true);
        return p;
    }
}

package com.innogrid.aims.scheduler.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 시나리오 — 같은 워크로드 묶음을 <b>나이순</b>과 <b>크기순</b>으로 power 엔진에 잇달아 배치하면 통합 결과가 어떻게 달라지는가,
 * 그리고 통합 여유가 결정 세부에 어떻게 보이는가.
 *
 * <pre>
 * 클러스터  가동 1대(16코어, CPU 25% = 4 사용) · 빈 2대(16코어, CPU 1%) · 용량 500W · 유휴 150W · 정책 사용률 상한 80%
 *           → 가동 노드는 80%까지 8코어 더, 빈 노드는 12코어까지 받을 수 있다
 * 워크로드  등록 순서(나이) 2·4·6·8 코어 (전부 NORMAL, 합 20 = 가동 여유 8 + 빈 노드 1대 12)
 *
 * A. 온도 미계측(열 점수 중립 0.5) — 전력만 본다
 *    첫 배치의 통합여유가 양수(가동 노드가 이김). 이 조합에서는 나이순·크기순 모두 빈 노드 1대만 깨우고 150W가 남는다 —
 *    power 엔진은 점수로 고르므로 단순 first-fit처럼 순서에 좌우되지 않는다. 크기순이 나빠지지 않음을 고정한다.
 * B. 온도 계측(가동 50°C, 빈 38°C) — 빈 노드의 낮은 온도가 열 점수에서 유리
 *    첫 배치의 통합여유가 음수(빈 노드가 이김) → 순서와 무관하게 빈 노드부터 깨운다. 이 상황을 수치로 남긴다.
 * </pre>
 */
class PlacementOrderScenarioTest {

    private final PowerAwareScheduler engine = new PowerAwareScheduler();
    private final PowerModel powerModel = new PowerModel(new SchedulerProperties());

    @DisplayName("시나리오 A 전력만 볼 때 크기순은 나이순보다 빈 노드를 더 깨우지 않고 첫 배치는 가동 노드다")
    @Test
    void scenario_a_size_order_no_extra_wake() {
        Result age = place(workloads(), 0);
        Result size = place(sortedBySize(workloads()), 0);

        System.out.println(table("A. 온도 미계측", age, size));
        assertTrue(size.wokenIdle <= age.wokenIdle, "크기순은 빈 노드를 더 깨우지 않는다: 나이순 %d vs 크기순 %d".formatted(age.wokenIdle, size.wokenIdle));
        assertTrue(size.reclaimable >= age.reclaimable, "남는 유휴 전력(회수 가능)이 줄지 않는다");
        assertTrue(age.firstMargin > 0, "전력만 보면 첫 배치는 가동 노드(여유 양수): " + age.firstMargin);
        // 이 합성 클러스터에서는 두 순서가 같은 결과(빈 노드 1대·150W)다 — power 엔진은 점수로 고르므로 단순 first-fit처럼
        // 순서에 좌우되지 않는다. 크기순의 이점은 노드·워크로드 조합에 따라 나타나며, 여기서는 "나빠지지 않음"만 고정한다.
    }

    @DisplayName("시나리오 B 빈 노드가 차가우면 통합여유가 음수로 보이고 순서로는 못 막는다")
    @Test
    void scenario_b_cold_idle_negative_margin() {
        Result age = place(workloads(), 1);
        Result size = place(sortedBySize(workloads()), 1);

        System.out.println(table("B. 온도 계측(빈 노드 38°C)", age, size));
        assertNotNull(age.firstMargin);
        assertTrue(age.firstMargin < 0, "빈 노드의 열 점수 이점이 깨우는 전력 비용을 넘는다: " + age.firstMargin);
        assertEquals(age.wokenIdle, size.wokenIdle, "이 상황은 배치 순서가 아니라 점수 구성의 문제 — 여유 값을 보이게 하는 이유");
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private record Result(String cpus, Set<String> usedNodes, int wokenIdle, double reclaimable, Double firstMargin) {}

    /** thermalMode 0 = 온도 미계측(0°C → 열 점수 중립), 1 = 계측(가동 50°C · 빈 38°C). */
    private Result place(List<Workload> order, int thermalMode) {
        double tActive = thermalMode == 0 ? 0 : 50, tIdle = thermalMode == 0 ? 0 : 38;
        List<NodeResource> nodes = List.of(
                new NodeResource("mdc-01", "n-active", 16, 65536, 0, 25, 30, 0, 260, 500, tActive, 0, 0),
                new NodeResource("mdc-01", "n-idle-1", 16, 65536, 0, 1, 10, 0, 150, 500, tIdle, 0, 0),
                new NodeResource("mdc-01", "n-idle-2", 16, 65536, 0, 1, 10, 0, 150, 500, tIdle, 0, 0));
        PlacementContext ctx = PlacementContext.of(nodes, new SchedulingPolicy("p", "d"));
        Set<String> used = new LinkedHashSet<>();
        Set<String> woken = new LinkedHashSet<>();
        Double firstMargin = null;
        StringBuilder cpus = new StringBuilder();
        for (Workload w : order) {
            SchedulingResult r = engine.schedule(w, ctx);
            assertTrue(r.success(), "배치 실패: " + r.reason());
            if (firstMargin == null) firstMargin = r.candidateScores().get(0).details().get("통합여유");
            used.add(r.selectedNodeId());
            if (r.selectedNodeId().startsWith("n-idle")) woken.add(r.selectedNodeId());
            ctx.reserve(r.selectedNodeId(), w);
            cpus.append(cpus.length() == 0 ? "" : "·").append(w.getRequiredCpu());
        }
        double reclaimable = ctx.nodes().stream().filter(n -> !powerModel.isActive(n)).mapToDouble(powerModel::idlePower).sum();
        return new Result(cpus.toString(), used, woken.size(), reclaimable, firstMargin);
    }

    private static List<Workload> workloads() {
        List<Workload> list = new ArrayList<>();
        int[] cpus = {2, 4, 6, 8};
        for (int i = 0; i < cpus.length; i++) list.add(new Workload("w" + i, cpus[i], 1024));
        return list;
    }

    private static List<Workload> sortedBySize(List<Workload> ws) {
        List<Workload> out = new ArrayList<>(ws);
        out.sort(Comparator.comparingInt(Workload::getRequiredCpu).reversed());
        return out;
    }

    private static String table(String title, Result age, Result size) {
        return String.format("%n%s%n| 순서 | 배치 순서(코어) | 사용 노드 | 깨운 빈 노드 | 남은 회수 가능 전력 | 첫 배치 통합여유 |%n|---|---|---|---|---|---|%n"
                + "| 나이순 | %s | %d | %d | %.0fW | %s |%n| 크기순 | %s | %d | %d | %.0fW | %s |%n", title,
                age.cpus, age.usedNodes.size(), age.wokenIdle, age.reclaimable, age.firstMargin,
                size.cpus, size.usedNodes.size(), size.wokenIdle, size.reclaimable, size.firstMargin);
    }
}

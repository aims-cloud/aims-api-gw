package com.innogrid.aims.scheduler.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.MdcPowerBudgets;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 전략 비교의 <b>입력 동일성</b>을 지킨다 — 사본·평가기·제외 집합.
 *
 * <p>여기서 깨지는 성질은 응답을 봐서는 드러나지 않는다. 노드가 오염되어도, 평가기가 운영과 다른
 * 임계값을 봐도, 열 검사의 부작용이 전략마다 다르게 쌓여도 표는 정상적인 숫자를 채운다 —
 * <b>틀린 숫자를</b>. 그래서 성질 자체를 테스트로 고정한다.</p>
 *
 * <p>스프링을 쓰지 않는다. 노드는 {@code new NodeResource(...)}로 직접 만들고, 판정을 뒤집을
 * 수치는 주석에 계산 과정을 적어 둔다.</p>
 */
class BenchmarkContextsTest {

    private static final String COOL = "node-a";
    private static final String MID = "node-b";
    private static final String HOT = "node-c";

    /** 정책 임계 온도(75℃)를 넘긴 노드가 있어야 냉각 대기 관련 성질을 볼 수 있다. */
    private static final double HOT_CELSIUS = 90;

    // ── 사본 ──────────────────────────────────────────────────

    @DisplayName("사본에 예약을 쌓아도 기준 스냅샷은 그대로다")
    @Test
    void copy_does_not_touch_the_base_snapshot() {
        List<NodeResource> base = base();
        PlacementContext copy = BenchmarkContexts.copyOf(base, policy(), evaluator());

        copy.reserve(COOL, workload());
        copy.reserve(MID, workload());

        assertEquals(4, copy.node(COOL).orElseThrow().getReservedCpu(), "사본에는 예약이 쌓인다");
        for (NodeResource node : base) {
            assertEquals(0, node.getReservedCpu(),
                    node.getNodeId() + ": 기준 스냅샷이 오염되면 다음 전략의 입력이 달라져 비교가 무의미해진다");
            assertEquals(0, node.getReservedMemoryMb(), node.getNodeId());
        }
    }

    @DisplayName("사본의 노드는 식별자 오름차순이다")
    @Test
    void copy_sorts_nodes_by_id() {
        List<NodeResource> shuffled = new ArrayList<>(List.of(hot(), cool(), mid()));

        PlacementContext copy = BenchmarkContexts.copyOf(shuffled, policy(), evaluator());

        assertEquals(List.of(COOL, MID, HOT),
                copy.nodes().stream().map(NodeResource::getNodeId).toList(),
                "첫 적격 노드에서 멈추는 전략은 후보 순서에 좌우된다 — 정렬이 결정론의 근거다");
    }

    @DisplayName("사본이 정책·제외 목록·원장 스냅샷을 잇는다")
    @Test
    void copy_carries_policy_and_excluded_and_ledgers() {
        SchedulingPolicy policy = policy();
        PlacementContext source = PlacementContext.of(base(), policy, evaluator())
                .withExcluded(List.of(new ExcludedNode("mdc-01", "node-z", List.of("cpu사용률"))))
                .withLinkMatrix(LinkMatrix.of(List.of(new MdcLink("mdc-01", "mdc-02", 20))))
                .withMdcBudgets(new MdcPowerBudgets(Map.of("mdc-01", 4000.0)))
                .withPowerBudget(700.0);

        PlacementContext carried = BenchmarkContexts.copyOf(source, evaluator());

        assertSame(policy, carried.policy());
        assertEquals(List.of("node-z"), carried.excludedNodes().stream().map(ExcludedNode::nodeId).toList());
        assertEquals(1, carried.linkMatrix().size(), "링크 원장을 빠뜨리면 링크 제약이 아무 노드도 막지 않는다");
        assertEquals(1, carried.mdcBudgets().size(), "예산 원장을 빠뜨리면 MDC 합계 상한이 적용되지 않는다");
        assertNotNull(carried.powerBudgetWatts(), "실행 단위 전력 예산도 함께 이어야 한다");
        assertEquals(700.0, carried.powerBudgetWatts().doubleValue(), 1e-9);
        assertEquals(3, carried.nodes().size());

        // 3인자 형태는 원장을 만들어내지 않는다 — 호출자가 싣는 것이 계약이다.
        PlacementContext bare = BenchmarkContexts.copyOf(base(), policy, evaluator());
        assertSame(policy, bare.policy());
        assertTrue(bare.excludedNodes().isEmpty());
        assertSame(LinkMatrix.EMPTY, bare.linkMatrix());
        assertSame(MdcPowerBudgets.EMPTY, bare.mdcBudgets());
    }

    // ── 평가기 ────────────────────────────────────────────────

    @DisplayName("평가기는 부를 때마다 새 인스턴스다")
    @Test
    void fresh_evaluator_is_a_new_instance_each_call() {
        SchedulerProperties props = new SchedulerProperties();

        assertNotSame(BenchmarkContexts.freshEvaluator(props), BenchmarkContexts.freshEvaluator(props),
                "평가기를 셀 사이에 재사용하면 앞 셀의 열 기록이 뒤 셀의 판정으로 넘어온다");
    }

    @DisplayName("평가기가 냉각 대기 상태를 공유하지 않는다")
    @Test
    void fresh_evaluator_does_not_share_cooldown_state() {
        Workload workload = workload();

        // 평가기 A로 뜨거운 노드를 검사한다. 이 읽기 검사가 온도 모델에 임계 기록을 남긴다.
        PlacementContext a = BenchmarkContexts.copyOf(base(), policy(), evaluator());
        String first = a.exclusionReason(workload, a.node(HOT).orElseThrow());
        assertNotNull(first);
        assertTrue(first.startsWith("온도 초과"), first);

        // 평가기 B — 다른 전략이 보는 판. 기록을 물려받지 않는다.
        PlacementContext b = BenchmarkContexts.copyOf(base(), policy(), evaluator());
        String second = b.exclusionReason(workload, b.node(HOT).orElseThrow());
        assertFalse(second.contains("냉각 대기"),
                "제외 사유가 호출 순서에 따라 달라지면 전략 간 비교가 성립하지 않는다: " + second);
        assertTrue(second.startsWith("온도 초과"), second);

        // 같은 평가기를 다시 불러도 사유가 바뀌지 않는다 — 벤치마크에서는 냉각 대기 자체가 꺼져 있다.
        String again = a.exclusionReason(workload, a.node(HOT).orElseThrow());
        assertEquals(first, again, "한 셀 안에서 전수 평가 전략이 자기 발을 묶는 경로가 남아 있다");

        // 대조 — 기본 설정 평가기는 두 번째 검사에서 사유가 바뀐다. 이 비대칭이 막으려던 함정이다.
        ConstraintEvaluator shared = ConstraintEvaluator.withDefaults();
        PlacementContext legacy = PlacementContext.of(base(), policy(), shared);
        legacy.exclusionReason(workload, legacy.node(HOT).orElseThrow());
        assertTrue(legacy.exclusionReason(workload, legacy.node(HOT).orElseThrow()).contains("냉각 대기"),
                "기본 평가기의 이 성질이 사라졌다면 벤치마크의 냉각 대기 차단도 다시 검토해야 한다");
    }

    @DisplayName("평가기는 주입된 설정을 쓴다")
    @Test
    void fresh_evaluator_uses_injected_properties() {
        // 열관성 계수(온도 설정) — 60℃ 노드에 16코어 중 8코어를 얹는다.
        // 기본 0.15: 60 + 0.15 × 0.5 × 60 = 64.5℃ ≤ 임계 75℃ → 적격
        // 주입 1.00: 60 + 1.00 × 0.5 × 60 = 90.0℃ > 임계 75℃ → 제외
        NodeResource warm = new NodeResource("mdc-01", "node-w", 16, 65536, 0, 10, 10, 0, 100, 500, 60, 0, 0);
        Workload halfOfWarm = new Workload("job", 8, 8192);

        assertNull(reasonWith(new SchedulerProperties(), halfOfWarm, warm), "코드 기본값에서는 적격이어야 한다");

        SchedulerProperties inertial = new SchedulerProperties();
        inertial.getThermal().setInertiaK(1.0);
        String thermalReason = reasonWith(inertial, halfOfWarm, warm);
        assertNotNull(thermalReason,
                "주입된 열관성 계수를 무시하면 여기서 적격이 나온다 — 운영과 다른 임계값으로 낸 수치가 된다");
        assertTrue(thermalReason.startsWith("배치 후 예상 온도"), thermalReason);

        // 유휴 전력 비율(전력 설정) — 400W/용량 500W 노드에 8코어 중 4코어를 얹는다.
        // 기본 0.3: 400 + (500 − 150) × 0.5 = 575W > 용량 500W → 제외
        // 주입 0.9: 400 + (500 − 450) × 0.5 = 425W ≤ 용량 500W → 적격
        NodeResource loaded = new NodeResource("mdc-01", "node-p", 8, 32768, 0, 10, 10, 0, 400, 500, 0, 0, 0);
        Workload halfOfLoaded = new Workload("job", 4, 4096);

        String powerReason = reasonWith(new SchedulerProperties(), halfOfLoaded, loaded);
        assertNotNull(powerReason);
        assertTrue(powerReason.startsWith("배치 후 예상 전력"), powerReason);

        SchedulerProperties idleHeavy = new SchedulerProperties();
        idleHeavy.getPower().setIdleRatio(0.9);
        assertNull(reasonWith(idleHeavy, halfOfLoaded, loaded),
                "주입된 전력 설정이 평가기에 닿지 않으면 여기서 제외 사유가 남는다");
    }

    // ── 제외 집합 ─────────────────────────────────────────────

    @DisplayName("미리 계산한 제외 집합은 전 전략이 같은 것을 본다")
    @Test
    void precomputed_exclusions_are_shared_by_all_strategies() {
        Map<String, String> first = BenchmarkContexts.exclusions(workload(), probe());
        Map<String, String> second = BenchmarkContexts.exclusions(workload(), probe());

        assertEquals(first, second,
                "같은 워크로드·같은 스냅샷이 같은 제외 집합을 내지 않으면 전략 간 격차의 일부가 계산 순서의 산물이다");
        assertTrue(first.containsKey(HOT), "뜨거운 노드가 제외 집합에 없으면 이 테스트가 아무것도 지키지 않는다");
        assertEquals(List.of(HOT), List.copyOf(first.keySet()), "프로브의 노드 순서를 유지한다");
    }

    @DisplayName("제외 집합에는 부적격 노드만 담긴다")
    @Test
    void precomputed_exclusions_contain_only_ineligible_nodes() {
        Map<String, String> exclusions = BenchmarkContexts.exclusions(workload(), probe());

        assertFalse(exclusions.containsKey(COOL), "적격 노드를 제외 집합에 담으면 전 전략이 그 노드를 잃는다");
        assertFalse(exclusions.containsKey(MID));
        assertEquals(1, exclusions.size());
        exclusions.forEach((nodeId, reason) -> assertFalse(reason.isBlank(), nodeId + ": 사유가 비었다"));
    }

    @DisplayName("제외 집합을 미리 계산해도 냉각 대기가 남지 않는다")
    @Test
    void precomputed_exclusion_does_not_leak_cooldown_to_the_probe() {
        PlacementContext probe = probe();
        Map<String, String> once = BenchmarkContexts.exclusions(workload(), probe);
        assertTrue(once.get(HOT).startsWith("온도 초과"), once.get(HOT));

        // 같은 프로브로 다시 계산해도 사유가 바뀌지 않는다.
        Map<String, String> twice = BenchmarkContexts.exclusions(workload(), probe);
        assertEquals(once, twice, "사전 계산 자체가 프로브에 상태를 남기면 두 번째 값이 달라진다");
        assertFalse(twice.get(HOT).contains("냉각 대기"), twice.get(HOT));

        // 다음 셀의 새 평가기도 마찬가지다.
        PlacementContext next = BenchmarkContexts.copyOf(base(), policy(), evaluator());
        String reason = next.exclusionReason(workload(), next.node(HOT).orElseThrow());
        assertTrue(reason.startsWith("온도 초과"), reason);
        assertFalse(reason.contains("냉각 대기"), reason);
    }

    // ── 픽스처 ────────────────────────────────────────────────

    /** 벤치마크가 쓰는 평가기. 운영 설정 자리에 코드 기본값을 넣은 것뿐이고, 조립 경로는 같다. */
    private static ConstraintEvaluator evaluator() {
        return BenchmarkContexts.freshEvaluator(new SchedulerProperties());
    }

    private static PlacementContext probe() {
        return BenchmarkContexts.copyOf(base(), policy(), evaluator());
    }

    /** 노드는 가변이라 테스트마다 새로 만든다. */
    private static List<NodeResource> base() {
        return new ArrayList<>(List.of(cool(), mid(), hot()));
    }

    private static NodeResource cool() {
        return node(COOL, 45);
    }

    private static NodeResource mid() {
        return node(MID, 50);
    }

    private static NodeResource hot() {
        return node(HOT, HOT_CELSIUS);
    }

    /**
     * 자원·전력은 넉넉하고 온도만 다른 노드. 그래야 제외 사유가 온도에서 갈린다 —
     * 자원이 모자라면 자원 부족이 먼저 걸려 이 테스트들이 온도를 보지 못한다.
     */
    private static NodeResource node(String nodeId, double celsius) {
        return new NodeResource("mdc-01", nodeId, 16, 65536, 0, 10, 10, 0, 100, 500, celsius, 0, 0);
    }

    private static SchedulingPolicy policy() {
        return new SchedulingPolicy("bench", "전략 비교용");   // 임계 온도 75℃, CPU 80%, 온도·전력 제약 on
    }

    private static Workload workload() {
        return new Workload("job", 4, 8192);
    }

    /** 주입한 설정으로 조립한 평가기가 노드 한 대를 어떻게 판정하는지. */
    private static String reasonWith(SchedulerProperties props, Workload workload, NodeResource node) {
        PlacementContext ctx = BenchmarkContexts.copyOf(List.of(node), policy(),
                BenchmarkContexts.freshEvaluator(props));
        return ctx.exclusionReason(workload, ctx.nodes().get(0));
    }
}

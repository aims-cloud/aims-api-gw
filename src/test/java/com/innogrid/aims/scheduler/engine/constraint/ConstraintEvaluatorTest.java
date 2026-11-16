package com.innogrid.aims.scheduler.engine.constraint;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;

class ConstraintEvaluatorTest {

    private ConstraintEvaluator evaluator;
    private SchedulingPolicy policy;

    @BeforeEach
    void setUp() {
        evaluator = ConstraintEvaluator.withDefaults();
        // 기본값: CPU 80%, MEM 85%, 온도 75°C, 전력 사용률 90%
        policy = new SchedulingPolicy("test", "test");
    }

    // ── C1. 자원 가용성 ─────────────────────────────────────────

    @DisplayName("자원이 부족하면 제외한다")
    @Test
    void insufficient_resources_excluded() {
        NodeResource node = node("node-1", 8, 16384, 0, 0, 0, 200, 50);
        Workload workload = new Workload("big-job", 16, 4096);

        String reason = evaluate(workload, node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("자원 부족"), reason);
    }

    @DisplayName("GPU가 없는 노드는 GPU 워크로드를 거부한다")
    @Test
    void no_gpu_rejects_gpu_workload() {
        NodeResource node = node("node-1", 8, 16384, 0, 0, 0, 200, 50);
        Workload workload = new Workload("gpu-job", 2, 4096);
        workload.setRequiredGpu(1);

        assertNotNull(evaluate(workload, node));
    }

    @DisplayName("GPU가 있으면 GPU 워크로드를 받는다")
    @Test
    void gpu_node_accepts_gpu_workload() {
        NodeResource node = node("node-1", 8, 16384, 2, 0, 0, 200, 50);
        Workload workload = new Workload("gpu-job", 2, 4096);
        workload.setRequiredGpu(1);

        assertNull(evaluate(workload, node));
    }

    @DisplayName("예약분을 반영해 같은 노드에 중복 배치하지 않는다")
    @Test
    void reservation_blocks_same_node() {
        NodeResource node = node("node-1", 4, 8192, 0, 0, 0, 200, 50);
        Workload first = new Workload("job-1", 3, 1024);

        assertNull(evaluate(first, node), "첫 배치는 가능해야 한다");

        node.reserve(first.getRequiredCpu(), first.getRequiredMemoryMb(), first.getRequiredGpu());

        Workload second = new Workload("job-2", 3, 1024);
        assertNotNull(evaluate(second, node), "예약분을 빼면 두 번째는 들어갈 수 없다");
    }

    @DisplayName("예약 GPU 가 총 카드보다 많아도 GPU 를 요구하지 않는 워크로드는 자원 검사를 통과한다")
    @Test
    void gpu_free_workload_passes_resource_check_when_gpu_over_reserved() {
        NodeResource node = node("node-1", 8, 16384, 0, 0, 0, 200, 50);
        node.reserve(0, 0, 1);

        assertNull(evaluate(new Workload("job", 2, 4096), node));
    }

    @DisplayName("예약 GPU 가 총 카드보다 많은 노드에서도 GPU 를 요구하지 않는 워크로드의 CPU 는 그대로 검사한다")
    @Test
    void gpu_free_workload_still_checked_for_cpu_when_gpu_over_reserved() {
        NodeResource node = node("node-1", 8, 16384, 0, 0, 0, 200, 50);
        node.reserve(0, 0, 1);

        String reason = evaluate(new Workload("big-job", 16, 4096), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("자원 부족"), reason);
        assertTrue(reason.contains("GPU:0") && reason.contains("GPU:-1"), "문장은 요구 GPU 와 가용 GPU 를 그대로 찍는다 — " + reason);
    }

    // ── C2. 사용률 임계 ─────────────────────────────────────────

    @DisplayName("CPU 사용률 임계를 넘으면 제외한다")
    @Test
    void cpu_over_threshold_excluded() {
        NodeResource node = node("node-1", 8, 16384, 0, 85, 0, 200, 50);

        String reason = evaluate(new Workload("job", 1, 1024), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("CPU 사용률 초과"), reason);
    }

    @DisplayName("메모리 사용률 임계를 넘으면 제외한다")
    @Test
    void memory_over_threshold_excluded() {
        NodeResource node = node("node-1", 8, 16384, 0, 10, 90, 200, 50);

        String reason = evaluate(new Workload("job", 1, 512), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("메모리 사용률 초과"), reason);
    }

    // ── C3. 온도 ───────────────────────────────────────────────

    @DisplayName("현재 온도가 임계를 넘으면 제외한다")
    @Test
    void current_temp_over_threshold_excluded() {
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 200, 80);

        String reason = evaluate(new Workload("job", 1, 1024), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("온도 초과"), reason);
    }

    @DisplayName("배치 후 예상 온도가 임계를 넘으면 제외한다")
    @Test
    void predicted_temp_over_threshold_excluded() {
        // 72°C + 0.15 × (6/8) × 72 = 80.1°C > 75°C
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 200, 72);

        String reason = evaluate(new Workload("heavy", 6, 1024), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("배치 후 예상 온도"), reason);
    }

    @DisplayName("워크로드가 지정한 온도 제약을 적용한다")
    @Test
    void workload_temp_constraint_applied() {
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 200, 60);
        Workload workload = new Workload("job", 1, 1024);
        workload.setMaxTemperatureCelsius(50.0);

        String reason = evaluate(workload, node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("워크로드 온도 제약 초과"), reason);
    }

    @DisplayName("온도 인지를 끄면 온도로 제외하지 않는다")
    @Test
    void temp_aware_off_no_temp_exclusion() {
        policy.setTemperatureAwareEnabled(false);
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 200, 90);

        assertNull(evaluate(new Workload("job", 1, 1024), node));
    }

    // ── C4. 전력 ───────────────────────────────────────────────

    @DisplayName("전력 여유가 부족하면 제외한다")
    @Test
    void power_headroom_short_excluded() {
        // 여유 2% < 요구 여유 10%
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 490, 50);

        String reason = evaluate(new Workload("job", 1, 1024), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("전력 여유 부족"), reason);
    }

    @DisplayName("워크로드가 지정한 전력 예산을 적용한다")
    @Test
    void workload_power_budget_applied() {
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 200, 50);
        Workload workload = new Workload("job", 1, 1024);
        workload.setMaxPowerBudgetWatts(100.0);

        String reason = evaluate(workload, node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("워크로드 전력 제약 초과"), reason);
    }

    @DisplayName("배치 후 예상 전력이 용량을 넘으면 제외한다")
    @Test
    void predicted_power_over_capacity_excluded() {
        // 여유 12% 로 여유율 검사는 통과하지만, ΔP 175W 를 더하면 용량 500W 를 넘는다
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 440, 50);

        String reason = evaluate(new Workload("job", 4, 1024), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("배치 후 예상 전력"), reason);
    }

    @DisplayName("제약을 모두 통과하면 사유가 없다")
    @Test
    void all_constraints_pass_no_reason() {
        NodeResource node = node("node-1", 8, 16384, 0, 30, 40, 200, 50);

        assertNull(evaluate(new Workload("job", 2, 4096), node));
    }

    // ── 조회용 온도 상태 ────────────────────────────────────────

    @DisplayName("온도 상태를 경고와 임계로 구분한다")
    @Test
    void temp_status_warning_and_critical() {
        assertEquals(NodeResource.ThermalState.NORMAL,
                evaluator.thermalState(node("n", 8, 16384, 0, 10, 10, 200, 50), policy));
        assertEquals(NodeResource.ThermalState.WARNING,
                evaluator.thermalState(node("n", 8, 16384, 0, 10, 10, 200, 70), policy));
        assertEquals(NodeResource.ThermalState.CRITICAL,
                evaluator.thermalState(node("n", 8, 16384, 0, 10, 10, 200, 80), policy));
    }

    // ── helper ─────────────────────────────────────────────────

    // ── 배치 누적 반영 ──────────────────────────────────────────

    /**
     * 실측 전력만 보면 몇 개를 얹든 "하나 얹은 값"이 나와 용량 초과를 놓친다.
     * 8코어·250W·용량 500W 노드에 2코어를 연속으로 얹으면 실효 전력은
     * 250 → 337.5 → 425 → 512.5W 로 오르고, 마지막에 용량을 넘는다.
     */
    @DisplayName("연속 배치가 예상 전력에 누적된다")
    @Test
    void placements_accumulate_power() {
        NodeResource node = node("node-1", 8, 65536, 0, 10, 10, 250, 40);
        Workload workload = new Workload("job", 2, 1024);

        assertNull(evaluate(workload, node), "첫 배치는 가능하다");

        node.reserve(2, 1024, 0);
        assertNull(evaluate(workload, node), "두 번째도 아직 여유가 있다");

        node.reserve(2, 1024, 0);
        String reason = evaluate(workload, node);

        assertNotNull(reason, "세 번째는 용량을 넘어 걸러야 한다");
        assertTrue(reason.startsWith("배치 후 예상 전력"), reason);
    }

    /** 온도도 같은 구조다. 예약이 쌓이면 예상 온도가 함께 올라야 한다. */
    @DisplayName("연속 배치가 예상 온도에 누적된다")
    @Test
    void placements_accumulate_temp() {
        NodeResource node = node("node-1", 8, 65536, 0, 10, 10, 100, 70);
        policy.setMaxTemperatureCelsius(74);
        Workload workload = new Workload("job", 2, 1024);

        assertNull(evaluate(workload, node), "70°C 에 2코어면 예상 72.6°C 로 통과");

        node.reserve(4, 2048, 0);
        String reason = evaluate(workload, node);

        assertNotNull(reason, "예약 4코어가 쌓이면 예상 온도가 임계를 넘는다");
        assertTrue(reason.startsWith("배치 후 예상 온도"), reason);
    }

    /**
     * 예약으로 사용률 임계를 넘으면 걸러야 한다.
     * 이 검사가 실측만 보면 정책의 상한이 예약에 대해 전혀 적용되지 않는다.
     */
    @DisplayName("예약이 쌓여 사용률 임계를 넘으면 제외한다")
    @Test
    void reservation_over_utilization_excluded() {
        NodeResource node = node("node-1", 8, 65536, 0, 20, 10, 100, 40);
        policy.setMaxCpuUsagePercent(80);

        node.reserve(5, 1024, 0);   // 20% + 62.5%p = 82.5%

        String reason = evaluate(new Workload("job", 1, 1024), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("CPU 사용률 초과"), reason);
    }

    /**
     * 냉각 대기는 실제로 뜨거웠던 노드에만 걸어야 한다.
     * 예약 때문에 예상 온도가 넘은 것뿐이라면 이번 배치만 거르고 기록은 남기지 않는다.
     */
    @DisplayName("예약으로 예상 온도만 넘으면 냉각 대기를 걸지 않는다")
    @Test
    void predicted_temp_only_no_cooldown() {
        NodeResource node = node("node-1", 8, 65536, 0, 10, 10, 100, 70);
        policy.setMaxTemperatureCelsius(74);
        node.reserve(4, 2048, 0);

        assertNotNull(evaluate(new Workload("job", 2, 1024), node), "예상 온도로 걸러야 한다");
        assertFalse(evaluator.isCoolingDown("node-1"),
                "실측 70°C 는 임계 74°C 미만이다. 예측치로 10분 배제를 걸면 안 된다");
    }

    /** 예약이 없으면 실효값은 실측값과 같아 기존 동작이 그대로 유지된다. */
    @DisplayName("예약이 없으면 기존 판정과 같다")
    @Test
    void no_reservation_same_verdict() {
        NodeResource node = node("node-1", 8, 16384, 0, 40, 40, 200, 50);

        assertNull(evaluate(new Workload("job", 2, 1024), node));
    }

    /**
     * 요구 CPU 가 0 인 워크로드(메모리만 쓰는 작업)는 한계 전력이 0 이라 "배치 후 예상 전력"
     * 검사를 언제나 통과한다. 여유율 검사가 실측만 보면 예약이 아무리 쌓여도 이런 워크로드가
     * 무한히 들어간다 — 전력 상한이 사실상 없는 것과 같다.
     */
    @DisplayName("요구 CPU가 0이어도 예약이 쌓이면 전력 여유로 걸러낸다")
    @Test
    void cpu_0_reservation_power_filtered() {
        NodeResource node = node("node-1", 8, 65536, 0, 10, 10, 400, 40);
        policy.setMaxPowerUsagePercent(90);   // 여유 10% 미만이면 제외

        Workload memoryOnly = new Workload("mem-job", 0, 1024);
        assertNull(evaluate(memoryOnly, node), "실측 400W/500W 는 여유 20% 라 통과");

        node.reserve(2, 1024, 0);             // 실효 400 + 87.5 = 487.5W → 여유 2.5%

        String reason = evaluate(memoryOnly, node);
        assertNotNull(reason, "예약을 반영하면 여유가 부족하다");
        assertTrue(reason.startsWith("전력 여유 부족"), reason);
    }

    /** 전력을 수집하지 못한 노드는 예약이 있어도 전력 제약이 걸리지 않는다. 없는 값으로 막지 않는다. */
    @DisplayName("전력 미수집 노드는 예약이 있어도 전력으로 막지 않는다")
    @Test
    void unmeasured_power_not_blocked() {
        NodeResource node = node("node-1", 8, 65536, 0, 10, 10, 0, 40);
        node.reserve(4, 2048, 0);

        assertNull(evaluate(new Workload("job", 1, 1024), node),
                "전력이 0 인 것은 미수집이지 여유가 없다는 뜻이 아니다");
    }

    /** 온도 미수집 노드도 같다. 0℃ 에 예약 상승분을 얹어 임계를 넘겼다고 판정하면 안 된다. */
    @DisplayName("온도 미수집 노드는 예약이 있어도 온도로 막지 않는다")
    @Test
    void unmeasured_temp_not_blocked() {
        // 예약은 4코어까지만 잡는다. 그 이상이면 CPU 사용률 임계가 먼저 걸려
        // 온도 경로를 검증하지 못한다(10% + 6/8 = 85% > 80%).
        NodeResource node = node("node-1", 8, 65536, 0, 10, 10, 100, 0);
        node.reserve(4, 2048, 0);

        assertNull(evaluate(new Workload("job", 1, 1024), node));
    }

    /** 사용률 임계는 경계에서 통과해야 한다. 딱 맞는 값을 초과로 보면 배치가 한 건씩 줄어든다. */
    @DisplayName("사용률이 임계와 정확히 같으면 통과한다")
    @Test
    void utilization_at_threshold_passes() {
        NodeResource node = node("node-1", 8, 65536, 0, 30, 10, 100, 40);
        policy.setMaxCpuUsagePercent(80);

        node.reserve(4, 1024, 0);   // 30 + 50 = 80.0% — 초과가 아니다

        assertNull(evaluate(new Workload("job", 1, 1024), node));
    }

    /** 예약이 노드 전체를 덮어도 계산이 깨지지 않아야 한다(사용률 100% 상한). */
    @DisplayName("예약이 노드 전체를 덮어도 예외가 나지 않는다")
    @Test
    void full_node_reservation_no_throw() {
        NodeResource node = node("node-1", 8, 65536, 0, 90, 90, 480, 70);
        node.reserve(16, 131072, 0);   // 용량보다 큰 예약

        String reason = evaluate(new Workload("job", 1, 1024), node);

        assertNotNull(reason, "어떤 사유로든 제외돼야 한다");
    }

    private String evaluate(Workload workload, NodeResource node) {
        PlacementContext ctx = PlacementContext.of(List.of(node), policy);
        return evaluator.exclusionReason(workload, node, ctx);
    }

    private NodeResource node(String nodeId, int cpu, long memMb, int gpu,
                              double cpuUsage, double memUsage, double power, double temp) {
        return new NodeResource("mdc-01", nodeId, cpu, memMb, gpu,
                cpuUsage, memUsage, 30, power, 500, temp, 1000, 500);
    }
}

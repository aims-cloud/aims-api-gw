package com.innogrid.aims.scheduler.engine.constraint;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;

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

    @Test
    void 자원이_부족하면_제외한다() {
        NodeResource node = node("node-1", 8, 16384, 0, 0, 0, 200, 50);
        Workload workload = new Workload("big-job", 16, 4096);

        String reason = evaluate(workload, node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("자원 부족"), reason);
    }

    @Test
    void GPU가_없는_노드는_GPU_워크로드를_거부한다() {
        NodeResource node = node("node-1", 8, 16384, 0, 0, 0, 200, 50);
        Workload workload = new Workload("gpu-job", 2, 4096);
        workload.setRequiredGpu(1);

        assertNotNull(evaluate(workload, node));
    }

    @Test
    void GPU가_있으면_GPU_워크로드를_받는다() {
        NodeResource node = node("node-1", 8, 16384, 2, 0, 0, 200, 50);
        Workload workload = new Workload("gpu-job", 2, 4096);
        workload.setRequiredGpu(1);

        assertNull(evaluate(workload, node));
    }

    @Test
    void 예약분을_반영해_같은_노드에_중복_배치하지_않는다() {
        NodeResource node = node("node-1", 4, 8192, 0, 0, 0, 200, 50);
        Workload first = new Workload("job-1", 3, 1024);

        assertNull(evaluate(first, node), "첫 배치는 가능해야 한다");

        node.reserve(first.getRequiredCpu(), first.getRequiredMemoryMb(), first.getRequiredGpu());

        Workload second = new Workload("job-2", 3, 1024);
        assertNotNull(evaluate(second, node), "예약분을 빼면 두 번째는 들어갈 수 없다");
    }

    // ── C2. 사용률 임계 ─────────────────────────────────────────

    @Test
    void CPU_사용률_임계를_넘으면_제외한다() {
        NodeResource node = node("node-1", 8, 16384, 0, 85, 0, 200, 50);

        String reason = evaluate(new Workload("job", 1, 1024), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("CPU 사용률 초과"), reason);
    }

    @Test
    void 메모리_사용률_임계를_넘으면_제외한다() {
        NodeResource node = node("node-1", 8, 16384, 0, 10, 90, 200, 50);

        String reason = evaluate(new Workload("job", 1, 512), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("메모리 사용률 초과"), reason);
    }

    // ── C3. 온도 ───────────────────────────────────────────────

    @Test
    void 현재_온도가_임계를_넘으면_제외한다() {
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 200, 80);

        String reason = evaluate(new Workload("job", 1, 1024), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("온도 초과"), reason);
    }

    @Test
    void 배치_후_예상_온도가_임계를_넘으면_제외한다() {
        // 72°C + 0.15 × (6/8) × 72 = 80.1°C > 75°C
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 200, 72);

        String reason = evaluate(new Workload("heavy", 6, 1024), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("배치 후 예상 온도"), reason);
    }

    @Test
    void 워크로드가_지정한_온도_제약을_적용한다() {
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 200, 60);
        Workload workload = new Workload("job", 1, 1024);
        workload.setMaxTemperatureCelsius(50.0);

        String reason = evaluate(workload, node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("워크로드 온도 제약 초과"), reason);
    }

    @Test
    void 온도_인지를_끄면_온도로_제외하지_않는다() {
        policy.setTemperatureAwareEnabled(false);
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 200, 90);

        assertNull(evaluate(new Workload("job", 1, 1024), node));
    }

    // ── C4. 전력 ───────────────────────────────────────────────

    @Test
    void 전력_여유가_부족하면_제외한다() {
        // 여유 2% < 요구 여유 10%
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 490, 50);

        String reason = evaluate(new Workload("job", 1, 1024), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("전력 여유 부족"), reason);
    }

    @Test
    void 워크로드가_지정한_전력_예산을_적용한다() {
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 200, 50);
        Workload workload = new Workload("job", 1, 1024);
        workload.setMaxPowerBudgetWatts(100.0);

        String reason = evaluate(workload, node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("워크로드 전력 제약 초과"), reason);
    }

    @Test
    void 배치_후_예상_전력이_용량을_넘으면_제외한다() {
        // 여유 12% 로 여유율 검사는 통과하지만, ΔP 175W 를 더하면 용량 500W 를 넘는다
        NodeResource node = node("node-1", 8, 16384, 0, 10, 10, 440, 50);

        String reason = evaluate(new Workload("job", 4, 1024), node);

        assertNotNull(reason);
        assertTrue(reason.startsWith("배치 후 예상 전력"), reason);
    }

    @Test
    void 제약을_모두_통과하면_사유가_없다() {
        NodeResource node = node("node-1", 8, 16384, 0, 30, 40, 200, 50);

        assertNull(evaluate(new Workload("job", 2, 4096), node));
    }

    // ── 조회용 온도 상태 ────────────────────────────────────────

    @Test
    void 온도_상태를_경고와_임계로_구분한다() {
        assertEquals(NodeResource.ThermalState.NORMAL,
                evaluator.thermalState(node("n", 8, 16384, 0, 10, 10, 200, 50), policy));
        assertEquals(NodeResource.ThermalState.WARNING,
                evaluator.thermalState(node("n", 8, 16384, 0, 10, 10, 200, 70), policy));
        assertEquals(NodeResource.ThermalState.CRITICAL,
                evaluator.thermalState(node("n", 8, 16384, 0, 10, 10, 200, 80), policy));
    }

    // ── helper ─────────────────────────────────────────────────

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

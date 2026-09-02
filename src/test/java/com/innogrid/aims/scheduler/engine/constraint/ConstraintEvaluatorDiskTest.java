package com.innogrid.aims.scheduler.engine.constraint;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C1 디스크 검사 — 총량을 아는 노드에서만, 예약 누적 반영.
 *
 * <p>{@code requiredDiskGb} 는 저장·검증만 되고 C1 이 보지 않던 필드였다(2026-09-02 격차 분석).
 * 총량을 모르는 노드(0)는 검사하지 않는다 — 모르는 값으로 배치를 막지 않는다.</p>
 */
class ConstraintEvaluatorDiskTest {

    private final ConstraintEvaluator evaluator = ConstraintEvaluator.withDefaults();
    private final SchedulingPolicy policy = new SchedulingPolicy("test", "test");

    @Test
    void 요구_디스크가_가용을_넘으면_사유와_함께_제외한다() {
        NodeResource node = node(40).withDiskCapacityGb(500);       // 가용 300
        Workload w = workload(2, 350);

        String reason = evaluator.exclusionReason(w, node, context(node));

        assertNotNull(reason);
        assertTrue(reason.contains("디스크 부족"), reason);
        assertTrue(reason.contains("필요 350GB"), reason);
        assertTrue(reason.contains("가용 300GB"), reason);
    }

    @Test
    void 가용_안이면_통과한다() {
        NodeResource node = node(40).withDiskCapacityGb(500);       // 가용 300
        assertNull(evaluator.exclusionReason(workload(2, 300), node, context(node)));
    }

    @Test
    void 총량을_모르는_노드는_디스크_검사를_건너뛴다() {
        NodeResource node = node(40);                               // 총량 0 = 모름
        assertNull(evaluator.exclusionReason(workload(2, 10_000), node, context(node)),
                "모르는 값으로 배치를 막지 않는다");
    }

    @Test
    void 요구_디스크가_0_이면_검사할_것이_없다() {
        NodeResource node = node(99).withDiskCapacityGb(100);       // 가용 1
        assertNull(evaluator.exclusionReason(workload(2, 0), node, context(node)));
    }

    @Test
    void 예약이_쌓이면_두_번째_배치가_디스크에서_걸린다() {
        NodeResource node = node(0).withDiskCapacityGb(500);
        PlacementContext ctx = context(node);
        Workload first = workload(1, 300);
        Workload second = workload(1, 300);

        assertNull(evaluator.exclusionReason(first, node, ctx));
        ctx.reserve("n1", first);                                   // 디스크 300 예약 → 가용 200

        String reason = evaluator.exclusionReason(second, node, ctx);
        assertNotNull(reason);
        assertTrue(reason.contains("가용 200GB"), reason);
    }

    @Test
    void CPU_메모리_GPU_부족이_먼저_보고된다() {
        NodeResource node = node(0).withDiskCapacityGb(10);
        Workload w = workload(64, 500);                             // CPU 도 디스크도 부족

        String reason = evaluator.exclusionReason(w, node, context(node));
        assertTrue(reason.startsWith("자원 부족"), "기존 사유 문구·순서는 그대로: " + reason);
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private PlacementContext context(NodeResource node) {
        return PlacementContext.of(List.of(node), policy);
    }

    private static Workload workload(int cpu, int diskGb) {
        Workload w = new Workload("w", cpu, 512);
        w.setRequiredDiskGb(diskGb);
        return w;
    }

    private static NodeResource node(double diskUsagePercent) {
        return new NodeResource("mdc-01", "n1", 8, 16384, 0, 10, 20, diskUsagePercent, 200, 500, 45, 1000, 500);
    }
}

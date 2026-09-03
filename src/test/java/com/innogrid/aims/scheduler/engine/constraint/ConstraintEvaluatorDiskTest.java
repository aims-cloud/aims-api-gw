package com.innogrid.aims.scheduler.engine.constraint;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
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

    @DisplayName("요구 디스크가 가용을 넘으면 사유와 함께 제외한다")
    @Test
    void disk_shortfall_excluded() {
        NodeResource node = node(40).withDiskCapacityGb(500);       // 가용 300
        Workload w = workload(2, 350);

        String reason = evaluator.exclusionReason(w, node, context(node));

        assertNotNull(reason);
        assertTrue(reason.contains("디스크 부족"), reason);
        assertTrue(reason.contains("필요 350GB"), reason);
        assertTrue(reason.contains("가용 300GB"), reason);
    }

    @DisplayName("가용 안이면 통과한다")
    @Test
    void disk_within_available_passes() {
        NodeResource node = node(40).withDiskCapacityGb(500);       // 가용 300
        assertNull(evaluator.exclusionReason(workload(2, 300), node, context(node)));
    }

    @DisplayName("총량을 모르는 노드는 디스크 검사를 건너뛴다")
    @Test
    void unknown_disk_total_skips_check() {
        NodeResource node = node(40);                               // 총량 0 = 모름
        assertNull(evaluator.exclusionReason(workload(2, 10_000), node, context(node)),
                "모르는 값으로 배치를 막지 않는다");
    }

    @DisplayName("요구 디스크가 0 이면 검사할 것이 없다")
    @Test
    void required_disk_0_skips_check() {
        NodeResource node = node(99).withDiskCapacityGb(100);       // 가용 1
        assertNull(evaluator.exclusionReason(workload(2, 0), node, context(node)));
    }

    @DisplayName("예약이 쌓이면 두 번째 배치가 디스크에서 걸린다")
    @Test
    void reservation_blocks_second_on_disk() {
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

    @DisplayName("CPU 메모리 GPU 부족이 먼저 보고된다")
    @Test
    void cpu_mem_gpu_reported_first() {
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

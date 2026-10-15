package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 클러스터 스냅샷 계산기 — 무엇이 예약에 반응하고 무엇이 반응하지 않는지 값으로 가른다.
 *
 * <p>이 테스트의 핵심은 <b>축을 나눈 근거</b>를 고정하는 것이다. 실측 전력은 배치를 얹어도 변하지 않으므로
 * 전·후를 따로 두지 않았는데, 그 사실이 코드에서 깨지면(예: 실효값을 실측 자리에 넣으면) 여기서 걸린다.</p>
 */
class RunClusterSnapshotTest {

    private static final double ACTIVE_CPU = 5.0;

    private static final SchedulerProperties PROPS = new SchedulerProperties();

    private final RunClusterSnapshot snapshot =
            new RunClusterSnapshot(new PowerModel(PROPS), new ThermalModel(PROPS), PROPS);

    @DisplayName("계측 노드가 있는 판의 고정 축이 채워진다")
    @Test
    void fixed_axes_are_filled_when_power_is_measured() {
        PlacementContext ctx = ctx(List.of(busy(), idle()));

        RunClusterSnapshot.Fixed f = snapshot.fixedOf(ctx);

        assertEquals(2, f.nodeCount());
        assertEquals(0, f.excludedCount());
        assertEquals(2, f.measuredNodes());
        assertEquals(500.0, f.measuredPowerWatts());
        assertTrue(snapshot.movingOf(ctx, ACTIVE_CPU).reclaimableWatts() > 0, "유휴 노드가 있으면 회수 가능 전력이 잡힌다");
    }

    @DisplayName("전력을 못 잰 판은 전력 칸이 값 대신 비어 있다")
    @Test
    void unmeasured_power_yields_null_not_zero() {
        PlacementContext ctx = ctx(List.of(unmeasured(), unmeasured()));

        RunClusterSnapshot.Fixed f = snapshot.fixedOf(ctx);

        assertEquals(0, f.measuredNodes());
        assertNull(f.measuredPowerWatts(), "0 이 아니라 null — 0W 를 쟀다는 뜻이 되면 안 된다");
        assertNull(snapshot.movingOf(ctx, ACTIVE_CPU).reclaimableWatts());
    }

    @DisplayName("정격을 모르는 노드는 계측 수에도 전력 합에도 들지 않는다")
    @Test
    void a_node_without_capacity_is_not_measured() {
        // 전력은 있는데 정격이 0 — 유휴도 회수 가능 전력도 대체값이 되어 실측이라 부를 수 없다
        NodeResource noCapacity = new NodeResource("mdc-a", "n-nocap", 8, 16384, 0, 10, 10, 15, 300, 0, 40, 0, 0);

        assertEquals(0, snapshot.fixedOf(ctx(List.of(noCapacity))).measuredNodes());

        // 계측 노드 1대와 섞으면 합계가 한 대만 더하는지 두 대를 더하는지 갈린다.
        RunClusterSnapshot.Fixed mixed = snapshot.fixedOf(ctx(List.of(noCapacity, busy())));
        assertEquals(1, mixed.measuredNodes());
        assertEquals(200.0, mixed.measuredPowerWatts(),
                "정격을 모르는 노드의 전력이 합계에만 섞이면 옆 칸으로 나눈 대당 전력이 실제와 어긋난다");
    }

    @DisplayName("수집에서 빠진 노드 수가 따로 잡힌다")
    @Test
    void excluded_nodes_are_counted_apart() {
        PlacementContext ctx = ctx(List.of(busy()))
                .withExcluded(List.of(new ExcludedNode("mdc-a", "n-gone", List.of("cpu사용률"), ExcludedNode.Cause.MISSING_METRICS, 0)));

        RunClusterSnapshot.Fixed f = snapshot.fixedOf(ctx);

        assertEquals(1, f.nodeCount(), "후보에 오른 노드만 센다");
        assertEquals(1, f.excludedCount());
    }

    @DisplayName("노드가 하나도 없으면 값 없이 0 과 null 로 끝난다")
    @Test
    void an_empty_cluster_does_not_throw() {
        RunClusterSnapshot.Fixed f = snapshot.fixedOf(ctx(List.of()));
        RunClusterSnapshot.Moving m = snapshot.movingOf(ctx(List.of()), ACTIVE_CPU);

        assertEquals(0, f.nodeCount());
        assertNull(f.measuredPowerWatts());
        assertEquals(0, m.activeNodes());
        assertNull(m.avgTemperatureCelsius());
    }

    @DisplayName("예약을 얹으면 움직이는 축만 달라지고 고정 축은 그대로다")
    @Test
    void reservation_moves_only_the_moving_axes() {
        NodeResource node = idle();
        PlacementContext ctx = ctx(List.of(node));

        RunClusterSnapshot.Fixed before = snapshot.fixedOf(ctx);
        RunClusterSnapshot.Moving movingBefore = snapshot.movingOf(ctx, ACTIVE_CPU);

        ctx.reserve(node.getNodeId(), workload(4, 4096));

        RunClusterSnapshot.Fixed after = snapshot.fixedOf(ctx);
        RunClusterSnapshot.Moving movingAfter = snapshot.movingOf(ctx, ACTIVE_CPU);

        assertEquals(before, after, "고정 축은 예약으로 움직이지 않는다 — 그래서 전·후를 따로 두지 않는다");
        assertEquals(0, movingBefore.activeNodes(), "예약 전에는 유휴");
        assertEquals(1, movingAfter.activeNodes(), "예약이 실효 CPU 를 올려 가동으로 센다");
        assertTrue(movingBefore.reclaimableWatts() > 0, "깨우기 전에는 회수 가능 전력이 잡힌다");
        assertEquals(0.0, movingAfter.reclaimableWatts(), "깨어난 노드는 회수 대상에서 빠진다");
        assertNotEquals(movingBefore.avgTemperatureCelsius(), movingAfter.avgTemperatureCelsius(),
                "평균 온도는 실효 온도라 예약에 반응한다");
    }

    @DisplayName("가동 판정은 초과이지 이상이 아니다")
    @Test
    void the_active_threshold_is_strictly_greater() {
        // 실효 CPU 가 문턱과 정확히 같은 노드
        NodeResource onEdge = new NodeResource("mdc-a", "n-edge", 8, 16384, 0, ACTIVE_CPU, 10, 15, 300, 500, 40, 0, 0);

        assertEquals(0, snapshot.movingOf(ctx(List.of(onEdge)), ACTIVE_CPU).activeNodes(),
                "문턱과 같으면 가동이 아니다");
    }

    @DisplayName("온도를 못 잰 판은 평균 온도가 비어 있다")
    @Test
    void unmeasured_temperature_yields_null() {
        assertNull(snapshot.movingOf(ctx(List.of(unmeasured())), ACTIVE_CPU).avgTemperatureCelsius());
    }

    @DisplayName("전력과 온도를 소수 둘째 자리까지 적는다")
    @Test
    void values_are_rounded_to_two_decimals() {
        // 실측 123.456W·36.789℃ — 반올림을 빼면 그대로 내려가 진단 응답의 두 자리 표기와 갈린다
        NodeResource odd = new NodeResource("mdc-a", "n-odd", 8, 16384, 0, 1, 5, 15, 123.456, 500, 36.789, 0, 0);

        assertEquals(123.46, snapshot.fixedOf(ctx(List.of(odd))).measuredPowerWatts());
        assertEquals(36.79, snapshot.movingOf(ctx(List.of(odd)), ACTIVE_CPU).avgTemperatureCelsius());
    }

    @DisplayName("노브 지문은 같은 설정에서 같고 문턱이 바뀌면 달라진다")
    @Test
    void the_knobs_digest_moves_with_the_settings_that_define_the_numbers() {
        assertEquals(snapshot.knobsDigest(), snapshot.knobsDigest(), "같은 설정이면 같은 지문이다");

        SchedulerProperties changed = new SchedulerProperties();
        changed.getPower().setActiveCpuPercent(10.0);
        String moved = new RunClusterSnapshot(new PowerModel(changed), new ThermalModel(changed), changed).knobsDigest();

        assertNotEquals(snapshot.knobsDigest(), moved,
                "문턱이 바뀌면 가동 노드 수의 정의가 바뀐다 — 지문이 같으면 두 구간을 한 축으로 더하게 된다");
        assertEquals(12, moved.length());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private static PlacementContext ctx(List<NodeResource> nodes) {
        return PlacementContext.of(new java.util.ArrayList<>(nodes), new SchedulingPolicy("p", "d"),
                ConstraintEvaluator.withDefaults());
    }

    private static Workload workload(int cpu, int memoryMb) {
        return new Workload("w", cpu, memoryMb);
    }

    /** 가동 중인 계측 노드 — 200W / 정격 500W. */
    private static NodeResource busy() {
        return new NodeResource("mdc-a", "n-busy", 8, 16384, 0, 40, 30, 15, 200, 500, 45, 0, 0);
    }

    /** 유휴 계측 노드 — 300W / 정격 500W. */
    private static NodeResource idle() {
        return new NodeResource("mdc-a", "n-idle", 8, 16384, 0, 1, 5, 15, 300, 500, 35, 0, 0);
    }

    /** 전력·온도를 못 잰 노드 — 배포 서버가 지금 이 상태다. */
    private static NodeResource unmeasured() {
        return new NodeResource("mdc-a", "n-none", 8, 16384, 0, 10, 10, 15, 0, 0, 0, 0, 0);
    }
}

package com.innogrid.aims.scheduler.engine.constraint;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C3 냉각 대기 — 대기를 건 온도 상한 이하의 상한으로 평가할 때만 노드를 뺀다.
 *
 * <p>같은 노드 id 의 온도만 다른 노드 객체로 「지금 온도」와 「걸린 대기」를 떼어 본다. 시계는 한 번만 도는 갈고리를
 * 끼울 수 있어, 만료된 대기를 지우는 확인 도중 다른 평가가 새 대기를 거는 순서를 결정적으로 만든다.</p>
 */
class ConstraintEvaluatorCooldownTest {

    private static final Instant T0 = Instant.parse("2026-10-23T00:00:00Z");
    private static final String N = "hot:9100";

    private final SchedulerProperties props = new SchedulerProperties();
    private final HookClock clock = new HookClock(T0);
    private final ThermalModel thermal = new ThermalModel(props, clock);
    private final ConstraintEvaluator evaluator = new ConstraintEvaluator(new PowerModel(props), thermal);
    private final Workload workload = new Workload("job", 1, 1024);

    @DisplayName("낮은 온도 상한으로 걸린 냉각 대기는 상한이 더 높은 정책을 막지 않는다")
    @Test
    void lower_limit_cooldown_does_not_block_higher_limit_policy() {
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, evaluate(node(80), policy(70)).reason());

        assertNull(evaluate(node(80), policy(85)), "80℃ 는 85℃ 상한을 넘지 않았다");
    }

    @DisplayName("높은 온도 상한으로 걸린 냉각 대기는 같거나 낮은 상한의 정책을 막는다")
    @Test
    void higher_limit_cooldown_blocks_same_or_lower_limit_policy() {
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, evaluate(node(90), policy(85)).reason());

        assertTrue(evaluate(node(60), policy(85)).message().startsWith("냉각 대기 중"));
        assertTrue(evaluate(node(60), policy(70)).message().startsWith("냉각 대기 중"));
    }

    @DisplayName("냉각 대기의 남은 분은 그 정책을 막는 대기 가운데 가장 늦게 풀리는 것이다")
    @Test
    void cooldown_minutes_left_follow_latest_blocking_mark() {
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, evaluate(node(85), policy(80)).reason());
        clock.advance(Duration.ofMinutes(4));
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, evaluate(node(95), policy(90)).reason(),
                "80℃ 상한의 대기는 90℃ 상한 평가를 막지 않으므로 그 초과가 새로 기록된다");

        clock.advance(Duration.ofMinutes(2));                       // t0+6: 80℃ 대기 4분 · 90℃ 대기 8분
        assertEquals("냉각 대기 중 (8분 남음)", evaluate(node(60), policy(70)).message());
        assertNull(evaluate(node(60), policy(95)), "두 대기 모두 95℃ 상한보다 낮다");

        clock.advance(Duration.ofMinutes(4));                       // t0+10: 80℃ 대기 만료 · 90℃ 대기 4분
        assertEquals("냉각 대기 중 (4분 남음)", evaluate(node(60), policy(70)).message());

        clock.advance(Duration.ofMinutes(4));                       // t0+14: 둘 다 만료
        assertNull(evaluate(node(60), policy(70)));
    }

    @DisplayName("예약 GPU 가 총 카드보다 많은 노드에서 GPU 를 쓰지 않는 평가가 건 냉각 대기도 상한별로 막는다")
    @Test
    void over_reserved_gpu_node_cooldown_kept_per_limit() {
        NodeResource node = node(80);
        node.reserve(0, 0, 1);                                      // 가용 GPU -1 — GPU 0 워크로드는 자원 검사를 지나 C3 에 닿는다

        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, evaluate(node, policy(70)).reason());
        assertNull(evaluate(node, policy(85)));
        assertEquals(ExclusionReason.COOLING_DOWN, evaluate(node, policy(70)).reason());
    }

    @DisplayName("만료된 냉각 대기를 지우는 확인 도중 새로 걸린 냉각 대기는 지워지지 않는다")
    @Test
    void mark_left_during_expiry_check_survives() {
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, evaluate(node(80), policy(75)).reason());
        clock.advance(Duration.ofMinutes(props.getThermal().getCooldownMinutes()));   // 첫 대기 만료
        boolean[] ran = {false};
        clock.onNextInstant(() -> {                                 // 확인이 시계를 읽는 순간 다른 평가가 같은 노드에 새 대기를 건다
            assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, evaluate(node(80), policy(75)).reason());
            ran[0] = true;
        });

        evaluator.isCoolingDown(N);

        assertTrue(ran[0], "시험 전제: 갈고리가 확인 도중에 돌았다");
        assertTrue(evaluator.isCoolingDown(N), "확인 도중 걸린 새 대기가 남아 있어야 한다");
        assertEquals(props.getThermal().getCooldownMinutes(), evaluator.remainingCooldownMinutes(N));
    }

    @DisplayName("냉각 대기를 걸지 않는 평가기는 걸린 냉각 대기에 노드를 빼지만 새로 걸지 않는다")
    @Test
    void copy_without_cooldown_marking_blocks_but_marks_none() {
        ConstraintEvaluator copy = evaluator.copyWithoutCooldownMarking();

        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, evaluate(copy, node(80), policy(75)).reason());
        assertFalse(thermal.isCoolingDown(N), "사본은 온도 상한을 넘은 노드를 그 평가에서만 뺀다");

        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, evaluate(node(80), policy(75)).reason());
        assertTrue(thermal.isCoolingDown(N), "원래 평가기는 그대로 냉각 대기를 건다");

        assertEquals("냉각 대기 중 (10분 남음)", evaluate(copy, node(60), policy(75)).message(), "사본도 걸린 대기는 본다");
        assertNull(evaluate(copy, node(60), policy(80)), "75℃ 상한의 대기는 80℃ 상한 평가를 막지 않는다");
    }

    private Exclusion evaluate(NodeResource node, SchedulingPolicy policy) {
        return evaluate(evaluator, node, policy);
    }

    private Exclusion evaluate(ConstraintEvaluator by, NodeResource node, SchedulingPolicy policy) {
        return by.exclusion(workload, node, PlacementContext.of(List.of(node), policy));
    }

    private static NodeResource node(double temperature) {
        return new NodeResource("mdc-01", N, 8, 65536, 0, 10, 10, 30, 200, 500, temperature, 1000, 500);
    }

    private static SchedulingPolicy policy(double maxTemperature) {
        SchedulingPolicy policy = new SchedulingPolicy("p" + (int) maxTemperature, "d");
        policy.setMaxTemperatureCelsius(maxTemperature);
        return policy;
    }

    /** 돌릴 수 있는 시계 — 다음 {@code instant()} 한 번에만 도는 갈고리를 끼울 수 있다. */
    private static class HookClock extends Clock {
        private Instant now;
        private Runnable hook;

        HookClock(Instant now) { this.now = now; }

        void advance(Duration duration) { this.now = now.plus(duration); }

        void onNextInstant(Runnable hook) { this.hook = hook; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() {
            Runnable h = hook;
            hook = null;
            if (h != null) h.run();
            return now;
        }
    }
}

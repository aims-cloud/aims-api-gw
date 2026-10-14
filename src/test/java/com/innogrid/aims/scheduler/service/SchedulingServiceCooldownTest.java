package com.innogrid.aims.scheduler.service;

import static com.innogrid.aims.scheduler.service.SchedulingServiceBuilder.aSchedulingService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.ThermalCooldownResponse;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

/**
 * 냉각 대기 노드 목록 조회가 노드를 수집하는 조건.
 *
 * <p>대기 노드가 하나도 없으면 수집해도 응답이 비므로 수집하지 않아야 하고, 있으면 전처럼 한 번 수집해
 * 스냅샷 순서로 돌려줘야 한다. 수집하는 동안 대기가 풀린 노드는 목록에서 빠져야 한다 — 수집 전에
 * 대기 노드를 정해 두고 그 집합으로 거르면 이 조건이 깨진다.</p>
 */
class SchedulingServiceCooldownTest {

    private static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");

    private final SchedulerProperties properties = new SchedulerProperties();
    private final MutableClock clock = new MutableClock(T0);
    private final ThermalModel thermal = new ThermalModel(properties, clock);
    private final NodeResourceCollector collector = mock(NodeResourceCollector.class);
    private final SchedulingPolicyRepository policies = mock(SchedulingPolicyRepository.class);
    private final WorkloadRepository workloads = mock(WorkloadRepository.class);
    private final SchedulingService service = aSchedulingService()
            .workloads(workloads).policies(policies).collector(collector).properties(properties)
            .evaluator(new ConstraintEvaluator(new PowerModel(properties), thermal))
            .build();

    @DisplayName("냉각 대기 노드가 없으면 노드를 수집하지 않고, 있으면 한 번 수집해 스냅샷 순서로 돌려준다")
    @Test
    void cooldowns_collect_only_when_some_node_cooling_down() {
        when(collector.collect()).thenAnswer(inv -> snapshot());

        assertEquals(List.of(), service.getCoolingDownNodes());
        verify(collector, never()).collect();
        verifyNoInteractions(policies, workloads);

        // 표시 순서(n1 → n3)와 기록 맵의 순회 순서(n1 → n3)가 스냅샷 순서(n3 → n1)와 다르게 둔다 — 응답이 스냅샷 순서임을 가른다
        thermal.markCritical("n1:9100");
        thermal.markCritical("n3:9100");
        thermal.markCritical("gone:9100");   // 스냅샷에 없는 노드 — 응답에서 빠진다
        long cooldown = properties.getThermal().getCooldownMinutes();
        assertEquals(List.of(
                new ThermalCooldownResponse("mdc-01", "n3:9100", 45.0, "NORMAL", cooldown),
                new ThermalCooldownResponse("mdc-01", "n1:9100", 45.0, "NORMAL", cooldown)),
                service.getCoolingDownNodes());
        verify(collector, times(1)).collect();
    }

    @DisplayName("수집하는 동안 대기가 풀린 노드는 목록에서 빠지고 기록도 지워진다")
    @Test
    void node_expired_during_collection_is_dropped() {
        thermal.markCritical("n2:9100");
        when(collector.collect()).thenAnswer(inv -> {
            clock.advance(Duration.ofMinutes(properties.getThermal().getCooldownMinutes()));
            return snapshot();
        });

        assertEquals(List.of(), service.getCoolingDownNodes());
        verify(collector, times(1)).collect();
        thermal.markCritical("n2:9100");   // 조회가 기록을 지웠으면 지금 시각으로 다시 걸린다 — 남았으면 옛 시각이 유지돼 0분이다
        assertEquals(properties.getThermal().getCooldownMinutes(), thermal.remainingCooldownMinutes("n2:9100"),
                "수집 중 만료된 기록은 조회가 지운다");
    }

    private static NodeResourceCollector.Snapshot snapshot() {
        return new NodeResourceCollector.Snapshot(List.of(node("n3:9100"), node("n2:9100"), node("n1:9100")), List.of());
    }

    private static NodeResource node(String id) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, 30, 40, 30, 200, 500, 45, 1000, 500);
    }

    /** 수집 중에 시간을 앞으로 돌리기 위한 시계. */
    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { this.now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

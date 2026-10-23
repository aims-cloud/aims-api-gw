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
import com.innogrid.aims.scheduler.engine.constraint.Exclusion;
import com.innogrid.aims.scheduler.engine.constraint.ExclusionReason;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

/**
 * 냉각 대기 노드 목록 조회가 노드를 수집하는 조건.
 *
 * <p>대기 노드가 하나도 없으면 수집해도 응답이 비므로 수집하지 않아야 하고, 있으면 전처럼 한 번 수집해
 * 스냅샷 순서로 돌려줘야 한다. 수집하는 동안 대기가 풀린 노드는 목록에서 빠져야 한다 — 수집 전에
 * 대기 노드를 정해 두고 그 집합으로 거르면 이 조건이 깨진다.</p>
 *
 * <p>목록은 어느 온도 상한으로든 냉각 대기가 걸린 노드를 노드마다 한 행으로 내고, 남은 분은 그 노드에서 가장 늦게
 * 풀리는 대기 기준이다. 평가로 대기를 걸 때는 서비스와 같은 시계를 쓰는 평가기를 직접 부른다.</p>
 */
class SchedulingServiceCooldownTest {

    private static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
    /** 냉각 대기를 거는 평가의 온도 상한(기본 정책 값). */
    private static final double LIMIT = 75.0;

    private final SchedulerProperties properties = new SchedulerProperties();
    private final MutableClock clock = new MutableClock(T0);
    private final ThermalModel thermal = new ThermalModel(properties, clock);
    private final NodeResourceCollector collector = mock(NodeResourceCollector.class);
    private final SchedulingPolicyRepository policies = mock(SchedulingPolicyRepository.class);
    private final WorkloadRepository workloads = mock(WorkloadRepository.class);
    private final ConstraintEvaluator evaluator = new ConstraintEvaluator(new PowerModel(properties), thermal);
    private final SchedulingService service = aSchedulingService()
            .workloads(workloads).policies(policies).collector(collector).properties(properties)
            .evaluator(evaluator)
            .build();

    @DisplayName("냉각 대기 노드가 없으면 노드를 수집하지 않고, 있으면 한 번 수집해 스냅샷 순서로 돌려준다")
    @Test
    void cooldowns_collect_only_when_some_node_cooling_down() {
        when(collector.collect()).thenAnswer(inv -> snapshot());

        assertEquals(List.of(), service.getCoolingDownNodes());
        verify(collector, never()).collect();
        verifyNoInteractions(policies, workloads);

        // 표시 순서(n1 → n3)와 기록 맵의 순회 순서(n1 → n3)가 스냅샷 순서(n3 → n1)와 다르게 둔다 — 응답이 스냅샷 순서임을 가른다
        thermal.markCritical("n1:9100", LIMIT);
        thermal.markCritical("n3:9100", LIMIT);
        thermal.markCritical("gone:9100", LIMIT);   // 스냅샷에 없는 노드 — 응답에서 빠진다
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
        thermal.markCritical("n2:9100", LIMIT);
        when(collector.collect()).thenAnswer(inv -> {
            clock.advance(Duration.ofMinutes(properties.getThermal().getCooldownMinutes()));
            return snapshot();
        });

        assertEquals(List.of(), service.getCoolingDownNodes());
        verify(collector, times(1)).collect();
        thermal.markCritical("n2:9100", LIMIT);   // 조회가 기록을 지웠으면 지금 시각으로 다시 걸린다 — 남았으면 옛 시각이 유지돼 0분이다
        assertEquals(properties.getThermal().getCooldownMinutes(), thermal.remainingCooldownMinutes("n2:9100"),
                "수집 중 만료된 기록은 조회가 지운다");
    }

    @DisplayName("냉각 대기 목록은 노드마다 한 행이고, 남은 분은 그 노드에서 가장 늦게 풀리는 대기이며, 0 이하 상한으로 걸린 대기도 낸다")
    @Test
    void cooldown_list_one_row_per_node_with_latest_release() {
        when(collector.collect()).thenAnswer(inv -> snapshot());
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, exclusion(node("n1:9100", 72), 70).reason());
        clock.advance(Duration.ofMinutes(4));
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, exclusion(node("n1:9100", 85), 80).reason(),
                "70℃ 상한의 대기는 80℃ 상한 평가를 막지 않는다");
        clock.advance(Duration.ofMinutes(1));
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, exclusion(node("n3:9100", 0), -1).reason(),
                "온도를 못 잰 0℃ 노드도 0 이하 상한은 넘는다");

        clock.advance(Duration.ofMinutes(1));                           // T0+6: n1 대기 4분·8분 · n3 대기 9분
        assertEquals(List.of(
                new ThermalCooldownResponse("mdc-01", "n3:9100", 45.0, "NORMAL", 9),
                new ThermalCooldownResponse("mdc-01", "n1:9100", 45.0, "NORMAL", 8)),
                service.getCoolingDownNodes());
    }

    private Exclusion exclusion(NodeResource node, double maxTemperature) {
        SchedulingPolicy policy = new SchedulingPolicy("p", "d");
        policy.setMaxTemperatureCelsius(maxTemperature);
        return evaluator.exclusion(new Workload("job", 1, 1024), node, PlacementContext.of(List.of(node), policy));
    }

    private static NodeResource node(String id, double temperature) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, 30, 40, 30, 200, 500, temperature, 1000, 500);
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

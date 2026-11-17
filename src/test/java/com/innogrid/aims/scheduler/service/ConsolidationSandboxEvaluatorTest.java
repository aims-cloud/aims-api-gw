package com.innogrid.aims.scheduler.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.ConsolidationResponse.Candidate;
import com.innogrid.aims.scheduler.dto.ConsolidationResponse.Move;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.Exclusion;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PolicyChoice;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 통합 권고가 받을 노드를 실제 배치와 같은 평가기로 판정하는가 — 운영 설정과 걸린 냉각 대기.
 *
 * <p>기준 컨텍스트는 실제 배치처럼 공유 평가기를 싣는다. 테스트마다 같은 컨텍스트에서 받을 노드가 빠지는 까닭을
 * 먼저 확인해, 결과가 그 까닭에서 나온다는 것을 고정한다. 노드는 8코어 · 용량 500W, 비울 노드 idle은 CPU 2% ·
 * 150W · 45℃다. 워크로드는 목이 아니라 실제 엔티티라 온도·전력 제약이 비어 있다.</p>
 */
class ConsolidationSandboxEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-10-26T00:00:00Z");

    private final SchedulerProperties props = new SchedulerProperties();
    private final ThermalModel thermal = new ThermalModel(props, Clock.fixed(T0, ZoneOffset.UTC));
    private final ConstraintEvaluator shared = new ConstraintEvaluator(new PowerModel(props), thermal);
    private final SchedulingService schedulingService = mock(SchedulingService.class);
    private final WorkloadRepository workloadRepository = mock(WorkloadRepository.class);
    private final SchedulingPolicy policy = new SchedulingPolicy("common", "d");   // 75℃ · 온도·전력 인지 켬

    private final ConsolidationService service = new ConsolidationService(
            schedulingService, workloadRepository,
            new EngineRegistry(List.of(new PowerAwareScheduler())),
            new PowerModel(props), props);

    @DisplayName("통합 권고는 냉각 대기 중인 노드로 워크로드를 옮기지 않는다")
    @Test
    void cooling_down_receiver_not_used_as_move_target() {
        NodeResource host = node("host", 40, 250, 70);
        thermal.markCritical("host", 75.0);
        Workload w = workload(1);
        assertReason(ExclusionReason.COOLING_DOWN, w, host);

        Candidate idle = diagnose(w, host);

        assertBlockedForNoReceiver(idle);
    }

    @DisplayName("통합 권고는 운영 설정의 열관성 계수로 받을 노드의 예상 온도를 판정한다")
    @Test
    void uses_operational_inertia_setting() {
        props.getThermal().setInertiaK(0.5);
        NodeResource host = node("host", 40, 250, 70);
        Workload w = workload(2);
        assertReason(ExclusionReason.PREDICTED_TEMPERATURE_EXCEEDED, w, host);   // 70 × (1 + 0.5 × 2/8) = 78.75

        Candidate idle = diagnose(w, host);

        assertBlockedForNoReceiver(idle);
    }

    @DisplayName("통합 권고는 운영 설정의 유휴 전력 비율로 받을 노드의 예상 전력을 판정한다")
    @Test
    void uses_operational_idle_ratio_setting() {
        props.getPower().setIdleRatio(0.1);
        NodeResource host = node("host", 40, 400, 40);
        Workload w = workload(2);
        assertReason(ExclusionReason.PREDICTED_POWER_EXCEEDED, w, host);

        Candidate idle = diagnose(w, host);

        assertBlockedForNoReceiver(idle);
    }

    @DisplayName("낮은 온도 상한으로 걸린 냉각 대기는 통합 권고의 받을 노드를 막지 않는다")
    @Test
    void lower_limit_cooldown_does_not_block_consolidation() {
        NodeResource host = node("host", 40, 250, 70);
        thermal.markCritical("host", 60.0);
        Workload w = workload(1);
        assertNull(exclusion(w, host), "60℃ 상한의 대기는 75℃ 정책을 막지 않는다");

        Candidate idle = diagnose(w, host);

        assertTrue(idle.reclaimable(), idle.blockedReason());
        assertEquals(List.of("host"), idle.moves().stream().map(Move::toNodeId).toList());
    }

    @DisplayName("통합 권고는 온도 상한을 넘은 노드를 평가해도 냉각 대기를 걸지 않는다")
    @Test
    void diagnosis_leaves_no_cooldown() {
        NodeResource hot = node("hot", 40, 250, 80);
        NodeResource host = node("host", 40, 250, 50);
        Workload w = workload(1);
        assertFalse(thermal.anyCoolingDown(), "테스트 전제: 걸린 냉각 대기가 없다");

        Candidate idle = diagnose(w, hot, host);

        assertEquals(List.of("host"), idle.moves().stream().map(Move::toNodeId).toList());
        assertFalse(thermal.isCoolingDown("hot"), "80℃ 노드를 평가했지만 냉각 대기는 걸리지 않는다");
        assertFalse(thermal.anyCoolingDown());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** idle과 받을 노드들로 기준 컨텍스트를 세우고, idle에 놓인 워크로드 하나로 진단한다. */
    private Candidate diagnose(Workload workload, NodeResource... receivers) {
        List<NodeResource> nodes = new ArrayList<>();
        nodes.add(node("idle", 2, 150, 45));
        nodes.addAll(List.of(receivers));
        when(schedulingService.choosePolicy(null)).thenReturn(new PolicyChoice(policy, PolicyChoice.Source.COMMON, true));
        when(schedulingService.createContext(any())).thenAnswer(inv -> PlacementContext.of(copy(nodes), policy, shared));
        when(workloadRepository.findByAssignedNodeIdOrderByScheduledAtDesc("idle")).thenReturn(List.of(workload));
        return service.diagnose().candidates().stream()
                .filter(c -> c.nodeId().equals("idle")).findFirst().orElseThrow();
    }

    private void assertReason(ExclusionReason expected, Workload workload, NodeResource node) {
        Exclusion exclusion = exclusion(workload, node);
        assertNotNull(exclusion, "테스트 전제: 실제 배치는 이 노드를 뺀다");
        assertEquals(expected, exclusion.reason(), exclusion.message());
    }

    private Exclusion exclusion(Workload workload, NodeResource node) {
        return PlacementContext.of(List.of(node), policy, shared).exclusion(workload, node);
    }

    private static void assertBlockedForNoReceiver(Candidate idle) {
        assertFalse(idle.reclaimable(), "받을 노드가 실제 배치에서 빠지므로 반납할 수 없다");
        assertTrue(idle.moves().isEmpty(), idle.moves().toString());
        assertTrue(idle.blockedReason().contains("받을 노드가 없습니다"), idle.blockedReason());
    }

    private static Workload workload(int cpu) {
        Workload w = new Workload("move-me", cpu, 512);
        w.assignTo("mdc-01", "idle", 0, "test");
        return w;
    }

    private static NodeResource node(String id, double cpu, double power, double temperature) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, cpu, 10, 30, power, 500, temperature, 1_000_000, 500_000);
    }

    private static List<NodeResource> copy(List<NodeResource> nodes) {
        return nodes.stream().map(NodeResource::snapshot).toList();
    }
}

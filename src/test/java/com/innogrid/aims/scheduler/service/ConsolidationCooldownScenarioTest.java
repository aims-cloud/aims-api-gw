package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.innogrid.aims.scheduler.dto.ConsolidationResponse.Candidate;
import com.innogrid.aims.scheduler.dto.ConsolidationResponse.Move;
import com.innogrid.aims.scheduler.engine.constraint.Exclusion;
import com.innogrid.aims.scheduler.engine.constraint.ExclusionReason;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 통합 권고와 배치가 같은 냉각 대기를 보는가 — 스프링이 조립한 실제 경로.
 *
 * <p>진단 → {@code createContext} → 주입된 평가기 → 같은 {@link ThermalModel} 빈으로 이어지는 배선을 고정한다.
 * 온도 상한은 통합 권고가 쓰는 정책에서 읽고, 노드 온도는 그 상한에 대한 상대값으로 둔다. 공유 냉각 상태가 다른 클래스로
 * 새지 않게 앞뒤에서 이 노드들의 대기와 이 테스트가 저장한 워크로드를 지운다.</p>
 */
@SpringBootTest
class ConsolidationCooldownScenarioTest {

    private static final String SRC = "ccs-src:9100";
    private static final String HOST = "ccs-host:9100";

    @Autowired ConsolidationService consolidationService;
    @Autowired SchedulingService schedulingService;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired ThermalModel thermalModel;
    @MockitoBean NodeResourceCollector collector;

    private volatile double hostTemperature;
    private Workload saved;

    @BeforeEach
    void setUp() {
        clear();
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(
                new NodeResource("ccs-mdc", SRC, 8, 16384, 0, 2, 10, 30, 150, 500, 45, 1000, 500),
                new NodeResource("ccs-mdc", HOST, 8, 16384, 0, 40, 10, 30, 250, 500, hostTemperature, 1000, 500)),
                List.of()));
        Workload w = new Workload("ccs-w", 1, 512);
        w.assignTo("ccs-mdc", SRC, 0, "test");
        saved = workloadRepository.save(w);
    }

    @AfterEach
    void tearDown() {
        clear();
        if (saved != null) workloadRepository.deleteById(saved.getId());
    }

    private void clear() {
        thermalModel.clearCritical(SRC);
        thermalModel.clearCritical(HOST);
    }

    @DisplayName("통합 권고는 배치가 건 냉각 대기를 보고 받을 노드에서 빼며, 냉각 대기를 새로 걸지 않는다")
    @Test
    void consolidation_respects_placement_cooldown_and_adds_none() {
        SchedulingPolicy policy = schedulingService.choosePolicy(null).policy();
        double limit = policy.getMaxTemperatureCelsius();
        assertTrue(policy.isTemperatureAwareEnabled(), "테스트 전제: 통합 권고 정책이 온도를 본다");
        assertTrue(limit > 10, "테스트 전제: 온도 상한이 " + limit + "℃");

        hostTemperature = limit - 5;                                   // (0) 대기가 없으면 host 로 옮긴다
        Candidate before = src();
        assertTrue(before.reclaimable(), before.blockedReason());
        assertEquals(List.of(HOST), before.moves().stream().map(Move::toNodeId).toList());

        hostTemperature = limit + 5;                                   // (가) 실제 배치 평가가 상한 limit 의 대기를 건다
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, placementExclusion(policy).reason());

        hostTemperature = limit - 5;                                   // (나) 식은 뒤에도 대기가 남아 통합 권고가 host 를 뺀다
        Exclusion cooling = placementExclusion(policy);
        assertEquals(ExclusionReason.COOLING_DOWN, cooling.reason(), "테스트 전제: " + cooling.message());
        Candidate blocked = src();
        assertFalse(blocked.reclaimable());
        assertTrue(blocked.blockedReason().contains("받을 노드가 없습니다"), blocked.blockedReason());

        thermalModel.clearCritical(HOST);                              // (다) 통합 권고는 뜨거운 host 를 보고도 대기를 걸지 않는다
        hostTemperature = limit + 5;
        src();
        assertFalse(thermalModel.isCoolingDown(HOST), "통합 권고 조회가 냉각 대기를 걸었다");
    }

    private Exclusion placementExclusion(SchedulingPolicy policy) {
        PlacementContext ctx = schedulingService.createContext(policy);
        return ctx.exclusion(new Workload("ccs-draft", 1, 512), ctx.node(HOST).orElseThrow());
    }

    private Candidate src() {
        return consolidationService.diagnose().candidates().stream()
                .filter(c -> SRC.equals(c.nodeId())).findFirst().orElseThrow();
    }
}

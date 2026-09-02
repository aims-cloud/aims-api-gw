package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExcludedNode;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 후보가 하나도 없을 때 "왜 없는지"를 말하는가.
 *
 * <p>"활성 노드가 없습니다"만 남기면 노드가 다 꺼진 것인지 메트릭 저장소가 아픈 것인지
 * 구분할 수 없다. 수집기가 뺀 노드가 있으면 그 사실을 함께 적는다.</p>
 */
class SchedulingServiceExcludedTest {

    private final WorkloadRepository workloadRepository = mock(WorkloadRepository.class);
    private final SchedulingPolicyRepository policyRepository = mock(SchedulingPolicyRepository.class);
    private final NodeResourceCollector collector = mock(NodeResourceCollector.class);
    private final SchedulerProperties properties = new SchedulerProperties();

    private final SchedulingService service = new SchedulingService(
            workloadRepository, policyRepository, collector, mock(PlacementService.class),
            new EngineRegistry(List.of(new WeightedScoreScheduler())),
            ConstraintEvaluator.withDefaults(), properties);

    @Test
    void 후보가_없고_제외_노드가_있으면_실패_사유에_그_노드를_적는다() {
        given(List.of(), List.of(
                new ExcludedNode("mdc-01", "n1:9100", List.of("cpu사용률")),
                new ExcludedNode("mdc-01", "n2:9100", List.of("코어수"))));

        SchedulingResult result = service.scheduleWorkload(1L, "weighted");

        assertFalse(result.success());
        assertEquals("활성 노드가 없습니다 — 핵심 메트릭을 알 수 없어 제외한 노드 2대: n1:9100, n2:9100",
                result.reason());
    }

    @Test
    void 후보가_없고_제외_노드도_없으면_문구는_예전과_같다() {
        given(List.of(), List.of());

        assertEquals("활성 노드가 없습니다", service.dryRun(1L, null).reason());
    }

    @Test
    void 노드_단건_조회_404_사유에_수집_제외를_적는다() {
        given(List.of(node("n1:9100")), List.of(new ExcludedNode("mdc-01", "n2:9100", List.of("MemTotal"))));

        BusinessException excluded = assertThrows(BusinessException.class,
                () -> service.getNodeResource("n2:9100"));
        BusinessException unknown = assertThrows(BusinessException.class,
                () -> service.getNodeResource("n3:9100"));

        assertTrue(excluded.getMessage().contains("수집 제외"), excluded.getMessage());
        assertTrue(excluded.getMessage().contains("MemTotal"), excluded.getMessage());
        assertFalse(unknown.getMessage().contains("수집 제외"), unknown.getMessage());
    }

    @Test
    void 노드_목록_조회는_제외_노드를_담지_않는다() {
        given(List.of(node("n1:9100")), List.of(new ExcludedNode("mdc-01", "n2:9100", List.of("cpu사용률"))));

        List<NodeResource> nodes = service.getNodeResources();

        assertEquals(1, nodes.size(), "DTO 없이 나가는 목록이라 필드를 늘릴 수 없다 — 제외 노드는 진단 응답에서 본다");
        PlacementContext snapshot = service.nodeSnapshot();
        assertEquals(1, snapshot.excludedNodes().size());
    }

    // ── 픽스처 ──────────────────────────────────────────────────

    private void given(List<NodeResource> nodes, List<ExcludedNode> excluded) {
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(nodes, excluded));
        when(policyRepository.findFirstByActiveTrueAndResourceTypeIsNull())
                .thenReturn(Optional.of(new SchedulingPolicy("default", "기본")));
        when(workloadRepository.findById(anyLong()))
                .thenReturn(Optional.of(new Workload("w", 1, 512)));
        when(workloadRepository.findByStatusIn(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of());
    }

    private static NodeResource node(String id) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, 30, 40, 30, 200, 500, 45, 1000, 500);
    }
}

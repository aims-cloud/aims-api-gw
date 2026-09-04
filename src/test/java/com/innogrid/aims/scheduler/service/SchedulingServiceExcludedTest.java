package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
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

    private final SchedulingService service = SchedulingServiceBuilder.aSchedulingService()
            .workloads(workloadRepository).policies(policyRepository).collector(collector)
            .engines(new EngineRegistry(List.of(new WeightedScoreScheduler())))
            .properties(properties)
            .build();

    @DisplayName("후보가 없고 제외 노드가 있으면 실패 사유에 그 노드를 적는다")
    @Test
    void no_candidate_reason_names_excluded() {
        given(List.of(), List.of(
                new ExcludedNode("mdc-01", "n1:9100", List.of("cpu사용률")),
                new ExcludedNode("mdc-01", "n2:9100", List.of("코어수"))));

        SchedulingResult result = service.scheduleWorkload(1L, "weighted");

        assertFalse(result.success());
        assertEquals("활성 노드가 없습니다 — 핵심 메트릭을 알 수 없어 제외한 노드 2대: n1:9100, n2:9100",
                result.reason());
    }

    @DisplayName("후보가 없고 제외 노드도 없으면 문구는 예전과 같다")
    @Test
    void no_candidate_no_excluded_keeps_message() {
        given(List.of(), List.of());

        assertEquals("활성 노드가 없습니다", service.dryRun(1L, null).reason());
    }

    @DisplayName("노드 단건 조회 404 사유에 수집 제외를 적는다")
    @Test
    void node_404_reason_states_exclusion() {
        given(List.of(node("n1:9100")), List.of(new ExcludedNode("mdc-01", "n2:9100", List.of("MemTotal"))));

        BusinessException excluded = assertThrows(BusinessException.class,
                () -> service.getNodeResource("n2:9100"));
        BusinessException unknown = assertThrows(BusinessException.class,
                () -> service.getNodeResource("n3:9100"));

        assertTrue(excluded.getMessage().contains("수집 제외"), excluded.getMessage());
        assertTrue(excluded.getMessage().contains("MemTotal"), excluded.getMessage());
        assertFalse(unknown.getMessage().contains("수집 제외"), unknown.getMessage());
    }

    @DisplayName("노드 목록 조회는 제외 노드를 담지 않는다")
    @Test
    void node_list_omits_excluded_nodes() {
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

package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 통합 진단의 샌드박스 컨텍스트가 실제 배치 컨텍스트의 링크 스냅샷을 <b>그대로 잇는가</b>.
 *
 * <p>샌드박스는 노드 사본으로 새 컨텍스트를 만든다. 링크 스냅샷을 빠뜨리면 게이트가 켜진 정책에서
 * 시뮬레이션은 "옮길 수 있다"고 답하고 실제 배치는 C5 로 막히는 어긋남이 생긴다(설계 점검 4-D ㄱ).</p>
 */
class ConsolidationSandboxLinkTest {

    private final SchedulerProperties properties = new SchedulerProperties();
    private final ConsolidationService service = new ConsolidationService(
            mock(SchedulingService.class), mock(WorkloadRepository.class),
            new EngineRegistry(List.of(new PowerAwareScheduler())),
            new PowerModel(properties), properties);

    @DisplayName("샌드박스는 기준 컨텍스트의 링크 스냅샷을 그대로 잇고 제외 노드는 빼놓는다")
    @Test
    void sandbox_inherits_links_minus_excluded() {
        NodeResource n1 = node("n1"), n2 = node("n2");
        SchedulingPolicy policy = new SchedulingPolicy("p", "d");
        policy.setAffinityEnabled(true);
        LinkMatrix links = LinkMatrix.of(List.of(new MdcLink("mdc-01", "mdc-02", 20)));
        PlacementContext base = PlacementContext.of(List.of(n1, n2), policy).withLinkMatrix(links);

        PlacementContext sandbox = service.sandboxWithout(n1, base.nodes(), policy, base.linkMatrix());

        assertSame(links, sandbox.linkMatrix(), "실제 배치와 같은 링크 스냅샷");
        assertEquals(List.of("n2"), sandbox.nodes().stream().map(NodeResource::getNodeId).toList());
        assertNotSame(n2, sandbox.nodes().get(0), "노드는 사본 — 예약이 원본을 오염시키지 않는다");
    }

    @DisplayName("링크가 없는 기준 컨텍스트면 샌드박스도 비어 있다")
    @Test
    void no_links_yields_empty_sandbox() {
        NodeResource n1 = node("n1");
        SchedulingPolicy policy = new SchedulingPolicy("p", "d");

        PlacementContext sandbox = service.sandboxWithout(n1, List.of(n1, node("n2")), policy, LinkMatrix.EMPTY);

        assertSame(LinkMatrix.EMPTY, sandbox.linkMatrix());
        assertTrue(sandbox.nodes().stream().noneMatch(n -> n.getNodeId().equals("n1")));
    }

    private static NodeResource node(String id) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, 10, 10, 0, 0, 0, 0, 0, 0);
    }
}

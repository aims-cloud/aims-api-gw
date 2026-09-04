package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.entity.MdcLink;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.LinkMatrix;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.repository.MdcLinkRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 배치 컨텍스트가 링크 원장을 <b>정책이 켰을 때만, 실행당 1회</b> 읽는가.
 *
 * <p>기존 정책은 전부 {@code affinityEnabled=false} 라 배포 직후 원장 조회가 0회여야 한다 — 비용도 결과도 그대로.
 * 켠 정책은 스냅샷을 컨텍스트에 싣고, 게이트 판정은 정책과 워크로드를 함께 본다.</p>
 */
class SchedulingServiceLinkMatrixTest {

    private final NodeResourceCollector collector = mock(NodeResourceCollector.class);
    private final MdcLinkRepository linkRepository = mock(MdcLinkRepository.class);
    private final SchedulerProperties properties = new SchedulerProperties();

    private final SchedulingService service = SchedulingServiceBuilder.aSchedulingService()
            .collector(collector)
            .engines(new EngineRegistry(List.of(new WeightedScoreScheduler())))
            .properties(properties)
            .links(linkRepository)
            .build();

    @DisplayName("게이트가 꺼진 정책은 원장을 조회하지 않고 스냅샷이 비어 있다")
    @Test
    void gate_off_skips_ledger_empty_snapshot() {
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(List.of(node("n1")), List.of()));

        PlacementContext ctx = service.createContext(new SchedulingPolicy("off", "d"));

        verify(linkRepository, never()).findAll();
        assertSame(LinkMatrix.EMPTY, ctx.linkMatrix());
        Workload w = new Workload("w", 1, 512);
        w.setDataMdcId("mdc-02");
        assertFalse(ctx.linkAware(w), "정책이 꺼져 있으면 dataMdcId 가 있어도 게이트 off");
    }

    @DisplayName("게이트를 켠 정책은 원장을 한 번 읽어 스냅샷을 싣는다")
    @Test
    void gate_on_reads_ledger_once() {
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(List.of(node("n1")), List.of()));
        when(linkRepository.findAll()).thenReturn(List.of(new MdcLink("mdc-02", "mdc-01", 20)));
        SchedulingPolicy on = new SchedulingPolicy("on", "d");
        on.setAffinityEnabled(true);

        PlacementContext ctx = service.createContext(on);

        verify(linkRepository, times(1)).findAll();
        assertEquals(1, ctx.linkMatrix().size());
        assertEquals(20.0, ctx.linkMatrix().between("mdc-01", "mdc-02").orElseThrow().latencyMs());

        Workload with = new Workload("w", 1, 512);
        with.setDataMdcId("mdc-02");
        assertTrue(ctx.linkAware(with));
        assertFalse(ctx.linkAware(new Workload("w2", 1, 512)), "dataMdcId 가 없으면 게이트 off");
        Workload blank = new Workload("w3", 1, 512);
        blank.setDataMdcId("  ");
        assertFalse(ctx.linkAware(blank), "빈 문자열도 없음으로 본다");
    }

    @DisplayName("원장 없이 조립한 서비스는 켠 정책이어도 빈 스냅샷이다 기존 테스트 경로")
    @Test
    void no_ledger_empty_snapshot_with_gate_on() {
        SchedulingService withoutLedger = SchedulingServiceBuilder.aSchedulingService()
                .collector(collector)
                .engines(new EngineRegistry(List.of(new WeightedScoreScheduler())))
                .properties(properties)
                .build();   // 링크 원장을 붙이지 않는다
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(List.of(node("n1")), List.of()));
        SchedulingPolicy on = new SchedulingPolicy("on", "d");
        on.setAffinityEnabled(true);

        assertSame(LinkMatrix.EMPTY, withoutLedger.createContext(on).linkMatrix());
    }

    private static NodeResource node(String id) {
        return new NodeResource("mdc-01", id, 8, 16384, 0, 10, 10, 0, 0, 0, 0, 0, 0);
    }
}

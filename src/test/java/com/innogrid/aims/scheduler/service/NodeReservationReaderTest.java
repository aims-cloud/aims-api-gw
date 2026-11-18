package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.NodeReservationsResponse;
import com.innogrid.aims.scheduler.dto.NodeReservationsResponse.HeldWorkload;
import com.innogrid.aims.scheduler.dto.NodeReservationsResponse.Node;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.ReservationJudgment;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static com.innogrid.aims.scheduler.entity.Workload.WorkloadStatus.RUNNING;
import static com.innogrid.aims.scheduler.entity.Workload.WorkloadStatus.SCHEDULED;
import static com.innogrid.aims.scheduler.model.ReservationJudgment.EXPIRED;
import static com.innogrid.aims.scheduler.model.ReservationJudgment.FULL;
import static com.innogrid.aims.scheduler.model.ReservationJudgment.GPU_ONLY;
import static com.innogrid.aims.scheduler.model.ReservationJudgment.UNASSIGNED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드별 예약 내역 — 판정 규칙의 시각 끝값, readReservations 한 번의 now, 배치 경로와 같은 합계, 노드 묶기 · 정렬 · 필터.
 *
 * <p>판정 시각을 고정한 readReservations 결과 하나로 배치 컨텍스트 반영({@link PlacementContextAssembler#apply})과
 * 응답({@link NodeReservationReader#toResponse})을 함께 만들어 노드 합계를 대조한다. HTTP 두 번으로는 호출마다 now가
 * 달라 같은 값을 보장할 수 없어 이 단위에서 확인한다. 보존 기한 24시간 · 메트릭 지연 10분(기본값)이다.</p>
 */
class NodeReservationReaderTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 18, 3, 0, 0, 0, ZoneOffset.UTC);
    private static final int TTL = 24;
    private static final int LAG = 10;

    private final WorkloadRepository workloadRepository = mock(WorkloadRepository.class);
    private final PlacementDispatchService dispatch = mock(PlacementDispatchService.class);
    private final PlacementContextAssembler assembler = new PlacementContextAssembler(
            null, null, workloadRepository, new SchedulerProperties(), null, null, dispatch);

    // ── 판정 끝값 ──────────────────────────────────────────────

    @DisplayName("시작한 지 정확히 메트릭 지연만큼 지난 실행 중은 네 자원 모두 예약한다")
    @Test
    void running_exactly_at_metric_lag_is_full() {
        Workload w = workload(1L, "node-a", RUNNING, NOW.minusHours(1));

        assertEquals(FULL, judge(w, NOW.minusMinutes(LAG)));
        assertEquals(GPU_ONLY, judge(w, NOW.minusMinutes(LAG).minusNanos(1)));
    }

    @DisplayName("배치 시각이 정확히 보존 기한인 배치 대기는 예약하고 그보다 이르면 EXPIRED 다")
    @Test
    void scheduled_exactly_at_ttl_is_full() {
        assertEquals(FULL, judge(workload(1L, "node-a", SCHEDULED, NOW.minusHours(TTL)), null));
        assertEquals(EXPIRED, judge(workload(2L, "node-a", SCHEDULED, NOW.minusHours(TTL).minusSeconds(1)), null));
    }

    @DisplayName("시작 기록이 없는 실행 중은 배치 시각으로 재고 배치 시각도 없으면 네 자원 모두 예약한다")
    @Test
    void running_without_start_falls_back_to_scheduled_at() {
        assertEquals(GPU_ONLY, judge(workload(1L, "node-a", RUNNING, NOW.minusMinutes(LAG + 1)), null));
        assertEquals(FULL, judge(workload(2L, "node-a", RUNNING, NOW.minusMinutes(LAG - 1)), null));
        assertEquals(FULL, judge(workload(3L, "node-a", RUNNING, null), null));
    }

    @DisplayName("보존 기한은 배치 대기에만 걸고 늦게 시작한 실행 중은 시작 시각으로 잰다")
    @Test
    void ttl_does_not_apply_to_running() {
        Workload w = workload(1L, "node-a", RUNNING, NOW.minusHours(TTL + 5));

        assertEquals(FULL, judge(w, NOW.minusMinutes(1)));
    }

    @DisplayName("배정 노드가 없으면 상태와 무관하게 UNASSIGNED 다")
    @Test
    void no_node_is_unassigned() {
        assertEquals(UNASSIGNED, judge(workload(1L, null, SCHEDULED, NOW.minusMinutes(1)), null));
        assertEquals(UNASSIGNED, judge(workload(2L, null, RUNNING, NOW.minusHours(2)), NOW.minusHours(2)));
    }

    @DisplayName("판정마다 예약량은 배치 경로와 같은 식이다")
    @Test
    void judgment_amounts() {
        assertEquals(List.of(2L, 4096L, 1L, 0L), amounts(FULL, 2, 4096, 1, -5));
        assertEquals(List.of(0L, 0L, 1L, 0L), amounts(GPU_ONLY, 2, 4096, 1, 50));
        assertEquals(List.of(0L, 0L, 0L, 0L), amounts(EXPIRED, 2, 4096, 1, 50));
        assertEquals(List.of(0L, 0L, 0L, 0L), amounts(UNASSIGNED, 2, 4096, 1, 50));
    }

    // ── readReservations ───────────────────────────────────────

    @DisplayName("배치 대기 · 실행 중만 읽어 주어진 시각 하나로 판정하고 시작 시각은 실행 중에만 응답 행까지 싣는다")
    @Test
    void reading_uses_one_now_and_start_only_for_running() {
        held(workload(1L, "node-a", SCHEDULED, NOW.minusMinutes(5)),
                workload(2L, "node-a", RUNNING, NOW.minusHours(3)));
        starts(Map.of(1L, NOW.minusHours(1), 2L, NOW.minusMinutes(LAG)));

        PlacementContextAssembler.Reservations reading = assembler.readReservations(NOW);

        assertEquals(List.of(NOW, TTL, LAG), List.of(reading.now(), reading.ttlHours(), reading.lagMinutes()));
        assertNull(reading.held().get(0).startedAt(), "배치 대기는 전달 기록이 있어도 시작 시각을 싣지 않는다");
        assertEquals(NOW.minusMinutes(LAG), reading.held().get(1).startedAt());
        assertEquals(FULL, reading.held().get(1).judgment(), "정확히 지연만큼 지남 → 네 자원");
        verify(workloadRepository).findByStatusIn(List.of(SCHEDULED, RUNNING));
        List<HeldWorkload> rows = NodeReservationReader.toResponse(reading, null).nodes().get(0).workloads();
        assertNull(rows.get(0).startedAt(), "응답의 배치 대기 행도 시작 시각이 없다");
        assertEquals(NOW.minusMinutes(LAG), rows.get(1).startedAt(), "응답 행은 읽은 시작 시각을 그대로 싣는다");
    }

    @DisplayName("실행 중이 없으면 시작 시각을 조회하지 않는다")
    @Test
    void reading_without_running_skips_start_lookup() {
        held(workload(1L, "node-a", SCHEDULED, NOW.minusMinutes(5)));

        assembler.readReservations(NOW);

        verify(dispatch, never()).runningSince(anyCollection());
    }

    // ── 배치 경로와 같은 합계 ──────────────────────────────────

    @DisplayName("같은 readReservations 결과로 만든 응답의 노드 합계는 배치 컨텍스트의 노드 예약과 같다")
    @Test
    void node_sums_equal_context_reservations() {
        held(
                gpuWorkload(1L, "node-a", SCHEDULED, NOW.minusMinutes(5), 2, -10),
                gpuWorkload(2L, "node-a", RUNNING, NOW.minusHours(2), 1, 30),
                gpuWorkload(3L, "node-a", RUNNING, NOW.minusHours(2), 0, 30),
                gpuWorkload(4L, "node-a", SCHEDULED, NOW.minusHours(TTL + 1), 4, 70),
                gpuWorkload(5L, "node-b", RUNNING, NOW.minusMinutes(LAG), 2, 20),
                gpuWorkload(6L, "node-b", RUNNING, NOW.minusHours(1), 1, 20),
                gpuWorkload(7L, "node-x", SCHEDULED, NOW.minusMinutes(1), 1, 10),
                gpuWorkload(8L, null, SCHEDULED, NOW.minusMinutes(1), 1, 10));
        starts(Map.of(6L, NOW.minusMinutes(LAG).plusSeconds(1)));
        PlacementContextAssembler.Reservations reading = assembler.readReservations(NOW);

        PlacementContext context = PlacementContext.of(List.of(node("node-a"), node("node-b")),
                new SchedulingPolicy("test", "test"));
        PlacementContextAssembler.apply(context, reading);
        NodeReservationsResponse response = NodeReservationReader.toResponse(reading, null);

        assertEquals(List.of("node-a", "node-b", "node-x"), nodeIds(response), "스냅샷에 없는 노드(node-x)는 응답에만 나온다");
        for (int i = 0; i < 2; i++) {
            Node r = response.nodes().get(i);
            assertEquals(sums(context.node(r.nodeId()).orElseThrow()), sums(r), r.nodeId());
        }
        Node a = response.nodes().get(0);
        assertEquals(List.of(2L, 4096L, 3L, 0L), sums(a),
                "1 FULL(디스크 음수 → 0) + 2 · 3 GPU_ONLY(GPU 1 · 0) + 4 EXPIRED(0)");
        assertEquals(List.of(FULL, GPU_ONLY, GPU_ONLY, EXPIRED),
                a.workloads().stream().map(HeldWorkload::judgment).toList());
        assertTrue(context.node("node-x").isEmpty());
        assertEquals(1, response.unassignedCount());
    }

    // ── 응답 모양 ──────────────────────────────────────────────

    @DisplayName("노드는 id 순 · 행은 워크로드 id 순이고 판정 시각과 규칙 값을 싣는다")
    @Test
    void response_order_and_rule_values() {
        held(
                workload(9L, "node-b", SCHEDULED, NOW.minusMinutes(1)),
                workload(3L, "node-a", SCHEDULED, NOW.minusMinutes(1)),
                workload(1L, "node-a", SCHEDULED, NOW.minusMinutes(1)));

        NodeReservationsResponse r = NodeReservationReader.toResponse(assembler.readReservations(NOW), null);

        assertEquals(List.of(NOW, TTL, LAG), List.of(r.evaluatedAt(), r.reservationTtlHours(), r.metricLagMinutes()));
        assertEquals(List.of("node-a", "node-b"), nodeIds(r));
        assertEquals(List.of(1L, 3L), r.nodes().get(0).workloads().stream().map(HeldWorkload::workloadId).toList());
        HeldWorkload first = r.nodes().get(0).workloads().get(0);
        assertEquals(List.of("w-1", Workload.WorkloadStatus.SCHEDULED, NOW.minusMinutes(1)),
                List.of(first.name(), first.status(), first.scheduledAt()));
    }

    @DisplayName("노드 필터는 앞뒤 공백을 빼고 정확히 맞추며 배정 노드 없는 수는 필터와 무관하다")
    @Test
    void node_filter_trims_and_keeps_unassigned_count() {
        held(
                workload(1L, "node-a", SCHEDULED, NOW.minusMinutes(1)),
                workload(2L, "node-b", SCHEDULED, NOW.minusMinutes(1)),
                workload(3L, null, SCHEDULED, NOW.minusMinutes(1)));
        PlacementContextAssembler.Reservations reading = assembler.readReservations(NOW);

        NodeReservationsResponse only = NodeReservationReader.toResponse(reading, "  node-b ");
        assertEquals(List.of("node-b"), nodeIds(only));
        assertEquals(1, only.unassignedCount());

        NodeReservationsResponse unknown = NodeReservationReader.toResponse(reading, "node");
        assertTrue(unknown.nodes().isEmpty(), "부분 일치하지 않고, 없는 노드는 빈 목록");
        assertEquals(1, unknown.unassignedCount());

        assertEquals(2, NodeReservationReader.toResponse(reading, "   ").nodes().size(), "공백뿐이면 필터 없음");
    }

    @DisplayName("EXPIRED 만 있는 노드도 행과 합계 0 으로 나온다")
    @Test
    void expired_only_node_is_listed_with_zero_sums() {
        held(gpuWorkload(1L, "node-a", SCHEDULED, NOW.minusHours(TTL + 1), 2, 50));

        Node n = NodeReservationReader.toResponse(assembler.readReservations(NOW), null).nodes().get(0);

        assertEquals(List.of(0L, 0L, 0L, 0L), sums(n));
        HeldWorkload row = n.workloads().get(0);
        assertEquals(EXPIRED, row.judgment());
        assertEquals(List.of(0L, 0L, 0L, 0L), List.of((long) row.reservedCpu(), row.reservedMemoryMb(),
                (long) row.reservedGpu(), row.reservedDiskGb()));
    }

    @DisplayName("예약 상태 워크로드가 없으면 빈 목록과 0 이다")
    @Test
    void no_held_workloads() {
        held();

        NodeReservationsResponse r = NodeReservationReader.toResponse(assembler.readReservations(NOW), null);

        assertTrue(r.nodes().isEmpty());
        assertEquals(0, r.unassignedCount());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private static ReservationJudgment judge(Workload w, OffsetDateTime startedAt) {
        return PlacementContextAssembler.judge(w, startedAt, NOW, TTL, LAG);
    }

    private static List<Long> amounts(ReservationJudgment j, int cpu, long memoryMb, int gpu, long diskGb) {
        return List.of((long) j.cpu(cpu), j.memoryMb(memoryMb), (long) j.gpu(gpu), j.diskGb(diskGb));
    }

    private static List<Long> sums(Node n) {
        return List.of((long) n.reservedCpu(), n.reservedMemoryMb(), (long) n.reservedGpu(), n.reservedDiskGb());
    }

    private static List<Long> sums(NodeResource n) {
        return List.of((long) n.getReservedCpu(), n.getReservedMemoryMb(),
                (long) n.getReservedGpu(), n.reservedDiskGb());
    }

    private static List<String> nodeIds(NodeReservationsResponse r) {
        return r.nodes().stream().map(Node::nodeId).toList();
    }

    private void held(Workload... workloads) {
        when(workloadRepository.findByStatusIn(anyList())).thenReturn(new ArrayList<>(List.of(workloads)));
    }

    private void starts(Map<Long, OffsetDateTime> starts) {
        when(dispatch.runningSince(anyCollection())).thenReturn(starts);
    }

    /** CPU 8 · 메모리 16384MB · GPU 4 · 디스크 1000GB 노드. */
    private static NodeResource node(String id) {
        return new NodeResource("mdc-01", id, 8, 16384, 4, 20, 30, 30, 250, 500, 50, 1000, 500)
                .withDiskCapacityGb(1000);
    }

    /** CPU 2 · 메모리 4096MB를 요구하는 워크로드. 엔티티가 배치 시각을 직접 바꿀 수 없어 목으로 만든다. */
    private static Workload workload(Long id, String nodeId, Workload.WorkloadStatus status, OffsetDateTime at) {
        Workload w = mock(Workload.class);
        when(w.getId()).thenReturn(id);
        when(w.getName()).thenReturn("w-" + id);
        when(w.getAssignedNodeId()).thenReturn(nodeId);
        when(w.getStatus()).thenReturn(status);
        when(w.getScheduledAt()).thenReturn(at);
        when(w.getRequiredCpu()).thenReturn(2);
        when(w.getRequiredMemoryMb()).thenReturn(4096);
        return w;
    }

    /** {@link #workload}에 GPU 개수와 디스크(GB) 요구를 더한 워크로드. */
    private static Workload gpuWorkload(Long id, String nodeId, Workload.WorkloadStatus status, OffsetDateTime at,
                                        int gpu, int diskGb) {
        Workload w = workload(id, nodeId, status, at);
        when(w.getRequiredGpu()).thenReturn(gpu);
        when(w.getRequiredDiskGb()).thenReturn(diskGb);
        return w;
    }
}

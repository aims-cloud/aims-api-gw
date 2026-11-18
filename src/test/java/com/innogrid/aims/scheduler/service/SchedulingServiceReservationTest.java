package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 배치 컨텍스트의 예약 집계 규칙.
 *
 * <p>예약은 "배치됐지만 아직 노드 사용률 메트릭에 드러나지 않은 워크로드"를 뜻한다. 실행 중인
 * 워크로드는 시간이 지나면 메트릭에 잡히므로, 그때부터는 예약에서 빼야 한 워크로드가 두 번
 * 계산되지 않는다. 그 분기점이 {@code metric-lag-minutes}다.</p>
 *
 * <p>GPU 개수는 예외다. 카드 점유는 사용률 메트릭에 드러나지 않으므로, 실행 중인 워크로드의 GPU는 메트릭 지연·보존
 * 기한과 무관하게 끝 상태가 될 때까지 예약에 남는다.</p>
 *
 * <p>실행 중인 워크로드의 메트릭 지연은 시작 시각(현재 결정의 전달 기록이 RUNNING이 된 시각)부터 잰다. 그런 기록이 없으면
 * 배치 시각으로 대신하고, 보존 기한은 배치 대기에만 건다. 시작 시각을 주는 전달 서비스는 목 객체로 붙인다.</p>
 */
class SchedulingServiceReservationTest {

    private final WorkloadRepository workloadRepository = mock(WorkloadRepository.class);
    private final NodeResourceCollector nodeCollector = mock(NodeResourceCollector.class);
    private final SchedulerProperties properties = new SchedulerProperties();

    private final SchedulingService service = SchedulingServiceBuilder.aSchedulingService()
            .workloads(workloadRepository)
            .collector(nodeCollector)
            .engines(mock(EngineRegistry.class))
            .properties(properties)
            .build();

    private final PlacementDispatchService dispatch = mock(PlacementDispatchService.class);
    private final SchedulingService serviceWithStarts = SchedulingServiceBuilder.aSchedulingService()
            .workloads(workloadRepository)
            .collector(nodeCollector)
            .engines(mock(EngineRegistry.class))
            .properties(properties)
            .dispatch(dispatch)
            .build();

    @DisplayName("배치 대기중인 워크로드는 예약으로 잡는다")
    @Test
    void pending_workload_held_as_reservation() {
        int reserved = reservedCpuAfterContext(
                workload(Workload.WorkloadStatus.SCHEDULED, OffsetDateTime.now().minusMinutes(1)));

        assertEquals(2, reserved, "아직 실행 전이라 메트릭에 없다");
    }

    @DisplayName("오래 대기한 워크로드도 실행 전이면 예약으로 남는다")
    @Test
    void long_pending_stays_reserved() {
        int reserved = reservedCpuAfterContext(
                workload(Workload.WorkloadStatus.SCHEDULED, OffsetDateTime.now().minusHours(3)));

        assertEquals(2, reserved, "SCHEDULED 는 실행되지 않았으므로 언제까지나 메트릭에 없다");
    }

    @DisplayName("방금 시작한 실행중 워크로드는 예약에 남는다")
    @Test
    void just_started_running_stays_reserved() {
        int reserved = reservedCpuAfterContext(
                workload(Workload.WorkloadStatus.RUNNING, OffsetDateTime.now().minusMinutes(2)));

        assertEquals(2, reserved, "메트릭 지연(10분) 이내라 아직 사용률에 잡히지 않았다");
    }

    @DisplayName("오래 실행중인 워크로드는 예약에서 뺀다")
    @Test
    void long_running_released_from_reservation() {
        int reserved = reservedCpuAfterContext(
                workload(Workload.WorkloadStatus.RUNNING, OffsetDateTime.now().minusMinutes(30)));

        assertEquals(0, reserved, "이미 메트릭에 있는데 예약으로 또 잡으면 이중 계상이다");
    }

    @DisplayName("배치 시각을 모르는 행은 보수적으로 예약에 남긴다")
    @Test
    void unknown_placement_time_stays_reserved() {
        int reserved = reservedCpuAfterContext(
                workload(Workload.WorkloadStatus.RUNNING, null));

        assertEquals(2, reserved, "판단 근거가 없으면 자원을 넉넉히 잡는 쪽이 안전하다");
    }

    @DisplayName("TTL 을 넘긴 워크로드는 자원을 묶지 않는다")
    @Test
    void past_ttl_holds_no_resources() {
        int reserved = reservedCpuAfterContext(
                workload(Workload.WorkloadStatus.SCHEDULED, OffsetDateTime.now().minusHours(25)));

        assertEquals(0, reserved, "보존 기간(24시간)을 넘기면 예약을 푼다");
    }

    @DisplayName("메트릭 지연 설정을 늘리면 경계가 함께 움직인다")
    @Test
    void larger_metric_delay_moves_boundary() {
        properties.setMetricLagMinutes(60);

        int reserved = reservedCpuAfterContext(
                workload(Workload.WorkloadStatus.RUNNING, OffsetDateTime.now().minusMinutes(30)));

        assertEquals(2, reserved, "지연을 60분으로 두면 30분 전 실행은 아직 메트릭에 없다고 본다");
    }

    @DisplayName("메트릭 지연이 지난 실행중 워크로드는 GPU 만 예약에 남는다")
    @Test
    void running_past_metric_lag_keeps_only_gpu_reserved() {
        NodeResource node = nodeAfterContext(
                gpuWorkload(Workload.WorkloadStatus.RUNNING, OffsetDateTime.now().minusMinutes(30)));

        assertEquals(2, node.getReservedGpu(), "카드 점유는 사용률 메트릭에 드러나지 않아 실행이 끝날 때까지 예약으로 남는다");
        assertEquals(2, node.availableGpu(), "카드 4장 중 2장을 쓰고 있다");
        assertEquals(0, node.getReservedCpu(), "CPU 는 사용률 메트릭이 반영하므로 예약에서 뺀다");
        assertEquals(0, node.getReservedMemoryMb(), "메모리도 사용률 메트릭이 반영한다");
        assertEquals(0, node.reservedDiskGb(), "디스크도 사용률 메트릭이 반영한다");
    }

    @DisplayName("보존 기한이 지난 실행중 워크로드도 GPU 예약은 남는다")
    @Test
    void running_past_reservation_ttl_keeps_gpu() {
        NodeResource node = nodeAfterContext(
                gpuWorkload(Workload.WorkloadStatus.RUNNING, OffsetDateTime.now().minusHours(25)));

        assertEquals(2, node.getReservedGpu(), "보존 기한은 실행 전 배치를 거르는 장치라 실행 중인 카드를 풀지 않는다");
        assertEquals(0, node.getReservedCpu());
        assertEquals(0, node.reservedDiskGb());
    }

    @DisplayName("보존 기한이 지난 배치 대기 워크로드는 GPU 도 풀린다")
    @Test
    void scheduled_past_reservation_ttl_releases_gpu_too() {
        NodeResource node = nodeAfterContext(
                gpuWorkload(Workload.WorkloadStatus.SCHEDULED, OffsetDateTime.now().minusHours(25)));

        assertEquals(0, node.getReservedGpu(), "보존 기간(24시간)을 넘긴 배치 대기는 카드도 묶지 않는다");
        assertEquals(0, node.getReservedCpu());
        assertEquals(0, node.getReservedMemoryMb());
        assertEquals(0, node.reservedDiskGb());
    }

    @DisplayName("방금 시작한 실행중 워크로드는 GPU 를 한 번만 잡는다")
    @Test
    void just_started_running_reserves_gpu_once() {
        NodeResource node = nodeAfterContext(
                gpuWorkload(Workload.WorkloadStatus.RUNNING, OffsetDateTime.now().minusMinutes(2)));

        assertEquals(2, node.getReservedGpu(), "네 축 예약과 GPU 만 남기는 예약이 겹치면 4 가 된다");
        assertEquals(2, node.getReservedCpu());
        assertEquals(4096, node.getReservedMemoryMb());
        assertEquals(50, node.reservedDiskGb());
    }

    @DisplayName("배치 뒤 늦게 시작한 실행중 워크로드는 시작 뒤 메트릭 지연까지 네 축을 예약한다")
    @Test
    void late_started_running_keeps_full_reservation() {
        NodeResource node = nodeWithStarts(
                runningGpu(OffsetDateTime.now().minusMinutes(30)), Map.of(1L, OffsetDateTime.now().minusMinutes(2)));

        assertEquals(2, node.getReservedCpu(), "시작한 지 2분이라 아직 사용률에 잡히지 않았다 — 배치 시각(30분 전)으로 재면 빠진다");
        assertEquals(4096, node.getReservedMemoryMb());
        assertEquals(2, node.getReservedGpu(), "GPU 는 한 번만");
        assertEquals(50, node.reservedDiskGb());
    }

    @DisplayName("시작 뒤 메트릭 지연이 지난 실행중 워크로드는 GPU 만 남는다")
    @Test
    void running_past_metric_lag_since_start_keeps_only_gpu() {
        NodeResource node = nodeWithStarts(
                runningGpu(OffsetDateTime.now().minusMinutes(40)), Map.of(1L, OffsetDateTime.now().minusMinutes(20)));

        assertEquals(2, node.getReservedGpu());
        assertEquals(0, node.getReservedCpu(), "시작한 지 20분이라 사용률 메트릭이 반영한다");
        assertEquals(0, node.reservedDiskGb());
    }

    @DisplayName("보존 기한이 지난 뒤 시작한 실행중 워크로드도 시작 뒤 메트릭 지연까지 네 축을 예약한다")
    @Test
    void running_started_after_scheduled_ttl_keeps_full_reservation() {
        NodeResource node = nodeWithStarts(
                runningGpu(OffsetDateTime.now().minusHours(25)), Map.of(1L, OffsetDateTime.now().minusMinutes(2)));

        assertEquals(2, node.getReservedCpu(), "보존 기한은 배치 대기에만 건다 — 실행 중에 걸면 시작하자마자 빠진다");
        assertEquals(2, node.getReservedGpu());
    }

    @DisplayName("시작 기록이 없는 실행중 워크로드는 배치 시각을 기준으로 삼는다")
    @Test
    void running_without_start_record_falls_back_to_placement_time() {
        NodeResource node = nodeWithStarts(runningGpu(OffsetDateTime.now().minusMinutes(30)), Map.of());

        assertEquals(0, node.getReservedCpu(), "운영자 상태 변경으로 시작한 행 등 — 배치 시각(30분 전)으로 재어 전과 같다");
        assertEquals(2, node.getReservedGpu());
    }

    @DisplayName("실행중 워크로드가 없으면 시작 시각을 조회하지 않는다")
    @Test
    void no_running_workload_skips_start_lookup() {
        nodeWithStarts(gpuWorkload(Workload.WorkloadStatus.SCHEDULED, OffsetDateTime.now().minusMinutes(5)), Map.of());

        verify(dispatch, never()).runningSince(anyCollection());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** 컨텍스트를 만든 뒤 노드에 잡힌 예약 CPU. */
    private int reservedCpuAfterContext(Workload workload) {
        NodeResource node = new NodeResource("mdc-01", "node-1", 8, 16384, 0,
                20, 30, 30, 250, 500, 50, 1000, 500);
        when(nodeCollector.collect())
                .thenReturn(new NodeResourceCollector.Snapshot(List.of(node), List.of()));
        when(workloadRepository.findByStatusIn(anyList())).thenReturn(List.of(workload));

        PlacementContext context = service.createContext(new SchedulingPolicy("test", "test"));

        return context.node("node-1").orElseThrow().getReservedCpu();
    }

    /** 카드 4장·디스크 1000GB 노드로 컨텍스트를 만든 뒤 그 노드. */
    private NodeResource nodeAfterContext(Workload workload) {
        NodeResource node = new NodeResource("mdc-01", "node-1", 8, 16384, 4,
                20, 30, 30, 250, 500, 50, 1000, 500).withDiskCapacityGb(1000);
        when(nodeCollector.collect())
                .thenReturn(new NodeResourceCollector.Snapshot(List.of(node), List.of()));
        when(workloadRepository.findByStatusIn(anyList())).thenReturn(List.of(workload));

        PlacementContext context = service.createContext(new SchedulingPolicy("test", "test"));

        return context.node("node-1").orElseThrow();
    }

    /**
     * 상태와 배치 시각만 정해진 워크로드.
     * 엔티티가 배치 시각을 직접 바꿀 수 없어(내부에서 현재 시각을 넣는다) 목으로 만든다.
     */
    private Workload workload(Workload.WorkloadStatus status, OffsetDateTime scheduledAt) {
        Workload workload = mock(Workload.class);
        when(workload.getStatus()).thenReturn(status);
        when(workload.getScheduledAt()).thenReturn(scheduledAt);
        when(workload.getAssignedNodeId()).thenReturn("node-1");
        when(workload.getRequiredCpu()).thenReturn(2);
        when(workload.getRequiredMemoryMb()).thenReturn(4096);
        when(workload.getRequiredGpu()).thenReturn(0);
        return workload;
    }

    /** 전달 서비스(목 객체)가 주는 시작 시각으로 컨텍스트를 만든 뒤 카드 4장·디스크 1000GB 노드. */
    private NodeResource nodeWithStarts(Workload workload, Map<Long, OffsetDateTime> starts) {
        when(dispatch.runningSince(anyCollection())).thenReturn(starts);
        NodeResource node = new NodeResource("mdc-01", "node-1", 8, 16384, 4,
                20, 30, 30, 250, 500, 50, 1000, 500).withDiskCapacityGb(1000);
        when(nodeCollector.collect())
                .thenReturn(new NodeResourceCollector.Snapshot(List.of(node), List.of()));
        when(workloadRepository.findByStatusIn(anyList())).thenReturn(List.of(workload));

        return serviceWithStarts.createContext(new SchedulingPolicy("test", "test")).node("node-1").orElseThrow();
    }

    /** id 1인 실행 중 GPU 2장·디스크 50GB 워크로드. */
    private Workload runningGpu(OffsetDateTime scheduledAt) {
        Workload workload = gpuWorkload(Workload.WorkloadStatus.RUNNING, scheduledAt);
        when(workload.getId()).thenReturn(1L);
        return workload;
    }

    /** GPU 2장·디스크 50GB를 요구하는 워크로드(나머지는 {@link #workload}와 같다). */
    private Workload gpuWorkload(Workload.WorkloadStatus status, OffsetDateTime scheduledAt) {
        Workload workload = workload(status, scheduledAt);
        when(workload.getRequiredGpu()).thenReturn(2);
        when(workload.getRequiredDiskGb()).thenReturn(50);
        return workload;
    }
}

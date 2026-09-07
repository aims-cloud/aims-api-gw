package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 배치 컨텍스트의 예약 집계 규칙.
 *
 * <p>예약은 "배치됐지만 아직 노드 사용률 메트릭에 드러나지 않은 워크로드"를 뜻한다. 실행 중인
 * 워크로드는 시간이 지나면 메트릭에 잡히므로, 그때부터는 예약에서 빼야 한 워크로드가 두 번
 * 계산되지 않는다. 그 경계가 {@code metric-lag-minutes} 다.</p>
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
}

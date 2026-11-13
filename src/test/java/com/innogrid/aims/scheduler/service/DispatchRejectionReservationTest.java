package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.innogrid.aims.scheduler.dispatch.DispatcherRegistry;
import com.innogrid.aims.scheduler.dispatch.PlacementDispatcher;
import com.innogrid.aims.scheduler.dispatch.RecordingPlacementDispatcher;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.entity.PlacementDispatch.DispatchStatus;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 같은 실행 안에서 실행 계층이 받지 않은 배치의 예약은 되돌아가야 한다.
 *
 * <pre>
 * 노드      8코어 1대 (수집기 대역), CPU 10% → 가용 7.2코어
 * 워크로드  GPU 종류 2건: w1 HIGH 5코어, w2 NORMAL 5코어 (우선순위 순으로 w1 이 먼저)
 * 디스패처  w1 만 거부, 나머지 수락
 * 기대      w1 은 FAILED·배정 없음, w2 는 같은 노드에 SCHEDULED — w1 의 5코어 예약이 되돌아갔기 때문
 * </pre>
 */
@SpringBootTest
class DispatchRejectionReservationTest {

    private static final String NODE = "n8:9100";

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @MockitoBean NodeResourceCollector collector;
    @MockitoBean DispatcherRegistry registry;

    @AfterEach
    void clean() {
        historyRepository.findAll().stream().filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith("drr-")).forEach(historyRepository::delete);
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith("drr-")).forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith("drr-")).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith("drr-")).forEach(s -> scheduleService.delete(List.of(s.getId())));
    }

    @DisplayName("첫 배치가 거부되면 그 예약이 되돌아가 같은 실행의 다음 워크로드가 같은 노드에 배치된다")
    @Test
    void rejected_placement_frees_reservation_for_next_workload() {
        eightCoreNode();
        when(registry.current()).thenReturn(rejecting("drr-w1"));
        Workload w1 = save("drr-w1", Workload.WorkloadPriority.HIGH);
        Workload w2 = save("drr-w2", Workload.WorkloadPriority.NORMAL);

        ScheduleRun run = executionService.runNow(schedule("drr-s1"), "tester");

        assertEquals(1, run.getSuccessCount(), "w2 만 성공");
        assertEquals(1, run.getFailedCount(), "w1 은 거부로 실패");
        assertEquals(DispatchStatus.REJECTED,
                dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(w1.getId()).get(0).getStatus(),
                "w1 이 먼저 배치되어 거부돼야 한다 — 순서가 바뀌면 이 시험은 결함을 못 잡는다");
        Workload first = workloadRepository.findById(w1.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.FAILED, first.getStatus());
        assertNull(first.getAssignedNodeId());
        Workload second = workloadRepository.findById(w2.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.SCHEDULED, second.getStatus(), "거부된 예약이 남아 있으면 자원 부족으로 실패한다");
        assertEquals(NODE, second.getAssignedNodeId());
    }

    @DisplayName("둘 다 수락되면 첫 예약은 남아 두 번째는 자원 부족으로 실패한다 — 수락된 예약은 되돌리지 않는다")
    @Test
    void accepted_placement_keeps_reservation() {
        eightCoreNode();
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w1 = save("drr-a1", Workload.WorkloadPriority.HIGH);
        Workload w2 = save("drr-a2", Workload.WorkloadPriority.NORMAL);

        ScheduleRun run = executionService.runNow(schedule("drr-s2"), "tester");

        assertEquals(1, run.getSuccessCount());
        assertEquals(1, run.getFailedCount());
        assertEquals(Workload.WorkloadStatus.SCHEDULED, workloadRepository.findById(w1.getId()).orElseThrow().getStatus());
        Workload second = workloadRepository.findById(w2.getId()).orElseThrow();
        assertNotEquals(Workload.WorkloadStatus.SCHEDULED, second.getStatus());
        assertNull(second.getAssignedNodeId());
    }

    @DisplayName("즉시 실행이 실행 계층을 부를 때는 트랜잭션이 없고 배치 결정은 이미 커밋돼 있다")
    @Test
    void dispatcher_runs_outside_transaction_after_placement_commit() {
        eightCoreNode();
        boolean[] inTransaction = {true};
        Workload.WorkloadStatus[] committedStatus = {null};
        String[] committedNode = {null};
        when(registry.current()).thenReturn(new PlacementDispatcher() {
            @Override public String getMode() { return "test"; }
            @Override public Outcome dispatch(Request request) {
                inTransaction[0] = TransactionSynchronizationManager.isActualTransactionActive();
                Workload seen = workloadRepository.findById(request.workloadId()).orElseThrow();
                committedStatus[0] = seen.getStatus();
                committedNode[0] = seen.getAssignedNodeId();
                return Outcome.accepted(null, "ok");
            }
        });
        save("drr-t1", Workload.WorkloadPriority.NORMAL);

        ScheduleRun run = executionService.runNow(schedule("drr-s3"), "tester");

        assertEquals(1, run.getSuccessCount());
        assertFalse(inTransaction[0], "전달은 배치 결정이 커밋된 뒤 트랜잭션 밖에서 불려야 한다");
        assertEquals(Workload.WorkloadStatus.SCHEDULED, committedStatus[0], "전달 전에 배치 결정이 커밋돼 있어야 한다");
        assertEquals(NODE, committedNode[0]);
    }

    @DisplayName("노드 자원 — 예약을 되돌리면 네 축이 모두 원래 값으로 돌아오고 음수 디스크도 예약과 같은 보정을 받는다")
    @Test
    void node_unreserve_restores_all_axes() {
        NodeResource node = new NodeResource("mdc-01", NODE, 8, 65536, 2, 10, 20, 0, 0, 500, 0, 0, 0)
                .withDiskCapacityGb(100);
        double cpu = node.availableCpu();
        long memory = node.availableMemoryMb();
        int gpu = node.availableGpu();
        long disk = node.availableDiskGb();

        node.reserve(5, 1024, 1, 10);
        assertEquals(5, node.getReservedCpu());
        node.unreserve(5, 1024, 1, 10);

        assertEquals(0, node.getReservedCpu());
        assertEquals(0, node.getReservedMemoryMb());
        assertEquals(0, node.getReservedGpu());
        assertEquals(cpu, node.availableCpu());
        assertEquals(memory, node.availableMemoryMb());
        assertEquals(gpu, node.availableGpu());
        assertEquals(disk, node.availableDiskGb());

        node.reserve(1, 1, 0, -5);
        node.unreserve(1, 1, 0, -5);
        assertEquals(disk, node.availableDiskGb(), "음수 디스크는 예약·되돌리기 모두 0 으로 본다");
    }

    @DisplayName("배치 컨텍스트 — 없는 노드의 예약 되돌리기는 아무것도 바꾸지 않는다")
    @Test
    void context_unreserve_ignores_unknown_node() {
        NodeResource node = new NodeResource("mdc-01", NODE, 8, 65536, 2, 10, 20, 0, 0, 500, 0, 0, 0)
                .withDiskCapacityGb(100);
        long disk = node.availableDiskGb();
        PlacementContext context = PlacementContext.of(List.of(node), new SchedulingPolicy("drr-p", "d"));
        Workload w = new Workload("drr-unit", 5, 64);
        w.setRequiredGpu(1);
        w.setRequiredDiskGb(10);

        context.reserve(NODE, w);
        context.unreserve("없는 노드", w);

        assertEquals(5, node.getReservedCpu());
        assertEquals(64, node.getReservedMemoryMb());
        assertEquals(1, node.getReservedGpu());
        assertEquals(disk - 10, node.availableDiskGb());
        context.unreserve(NODE, w);
        assertEquals(0, node.getReservedCpu());
        assertEquals(0, node.getReservedMemoryMb());
        assertEquals(0, node.getReservedGpu());
        assertEquals(disk, node.availableDiskGb());
    }

    private void eightCoreNode() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", NODE, 8, 65536, 0, 10, 20, 0, 0, 500, 0, 0, 0)), List.of()));
    }

    private static PlacementDispatcher rejecting(String workloadName) {
        return new PlacementDispatcher() {
            @Override public String getMode() { return "test"; }
            @Override public Outcome dispatch(Request request) {
                return workloadName.equals(request.workloadName())
                        ? Outcome.rejected("노드 점검 중") : Outcome.accepted(null, "ok");
            }
        };
    }

    private long schedule(String id) {
        return scheduleService.create(new ScheduleRequest(id, id, Workload.ResourceType.GPU, "0 0 2 * * ?",
                false, true, "drr", "weighted", null, null, null, null)).id();
    }

    private Workload save(String name, Workload.WorkloadPriority priority) {
        Workload w = new Workload(name, 5, 64);
        w.setResourceType(Workload.ResourceType.GPU);
        w.setPriority(priority);
        return workloadRepository.saveAndFlush(w);
    }
}

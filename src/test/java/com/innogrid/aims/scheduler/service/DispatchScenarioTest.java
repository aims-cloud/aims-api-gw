package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.PlacementDispatch.DispatchStatus;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 시나리오 — 스케줄 실행 → 디스패치 → 콜백 → 예약 해제.
 *
 * <pre>
 * 노드      16코어 1대 (수집기 목 객체), CPU 10%
 * 워크로드  GPU 종류 3건: 4·2·2 코어 (정책 사용률 상한 80% → 12.8 코어 여유, 전부 배치 가능)
 * 실행      스케줄 즉시 실행 1회 → 결정 3건 → 디스패치 3행(실행 id 연결, ACCEPTED, SYSTEM)
 * 콜백      w1 RUNNING → COMPLETED, w2 RUNNING
 * 예약      다음 컨텍스트에서 예약 CPU = w2(2) + w3(2) = 4 — COMPLETED는 빠진다
 * </pre>
 */
@SpringBootTest
class DispatchScenarioTest {

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired SchedulingService schedulingService;
    @Autowired PlacementDispatchService dispatchService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @MockitoBean NodeResourceCollector collector;

    @AfterEach
    void clean() {
        historyRepository.findAll().stream().filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith("dsc-")).forEach(historyRepository::delete);
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith("dsc-")).forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith("dsc-")).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith("dsc-")).forEach(s -> scheduleService.delete(List.of(s.getId())));
    }

    @DisplayName("실행 3건 → 디스패치 3행(실행 id·SYSTEM·ACCEPTED) → 콜백 후 COMPLETED 워크로드는 다음 예약에서 빠진다")
    @Test
    void run_dispatch_callback_reservation() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", "big:9100", 16, 65536, 0, 10, 20, 0, 0, 500, 0, 0, 0)), List.of()));
        Workload w1 = save("dsc-w1", 4); Workload w2 = save("dsc-w2", 2); Workload w3 = save("dsc-w3", 2);

        long scheduleId = scheduleService.create(new ScheduleRequest("dsc-s", "dsc-s", Workload.ResourceType.GPU, "0 0 2 * * ?",
                false, true, "dsc", "weighted", null, null, null, null)).id();
        ScheduleRun run = executionService.runNow(scheduleId, "tester");
        assertEquals(3, run.getSuccessCount());

        List<PlacementDispatch> dispatches = dispatchRepository.findByScheduleRunIdOrderByRequestedAtAsc(run.getId());
        assertEquals(3, dispatches.size());
        assertTrue(dispatches.stream().allMatch(d -> d.getStatus() == DispatchStatus.ACCEPTED));
        assertTrue(dispatches.stream().allMatch(d -> "SYSTEM".equals(d.getCreatedBy())));
        assertTrue(dispatches.stream().allMatch(d -> "big:9100".equals(d.getNodeId())));

        PlacementContext before = schedulingService.createContext(new SchedulingPolicy("dsc-p", "d"));
        int reservedBefore = before.node("big:9100").orElseThrow().getReservedCpu();
        assertTrue(reservedBefore >= 8, "배치 직후 예약 CPU 는 최소 4+2+2: " + reservedBefore);

        PlacementDispatch d1 = dispatches.stream().filter(d -> d.getWorkloadId().equals(w1.getId())).findFirst().orElseThrow();
        PlacementDispatch d2 = dispatches.stream().filter(d -> d.getWorkloadId().equals(w2.getId())).findFirst().orElseThrow();
        dispatchService.applyResult(d1.getId(), "RUNNING", "job-1", null);
        dispatchService.applyResult(d1.getId(), "COMPLETED", null, null);
        dispatchService.applyResult(d2.getId(), "RUNNING", "job-2", null);

        assertEquals(Workload.WorkloadStatus.COMPLETED, workloadRepository.findById(w1.getId()).orElseThrow().getStatus());
        assertEquals(Workload.WorkloadStatus.RUNNING, workloadRepository.findById(w2.getId()).orElseThrow().getStatus());
        assertEquals(Workload.WorkloadStatus.SCHEDULED, workloadRepository.findById(w3.getId()).orElseThrow().getStatus());

        // COMPLETED인 w1(4코어)만 예약에서 빠진다. RUNNING인 w2는 메트릭 반영 지연(10분) 안이라 아직 예약으로 남는다.
        PlacementContext after = schedulingService.createContext(new SchedulingPolicy("dsc-p", "d"));
        int reservedAfter = after.node("big:9100").orElseThrow().getReservedCpu();
        assertEquals(reservedBefore - 4, reservedAfter, "예약 CPU 차이 = w1 의 4코어");
    }

    private Workload save(String name, int cpu) {
        Workload w = new Workload(name, cpu, 64);
        w.setResourceType(Workload.ResourceType.GPU);
        return workloadRepository.saveAndFlush(w);
    }
}

package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 배치 루프가 <b>중간에 끊겨도 그때까지의 집계를 남기는지</b> 본다.
 *
 * <p>고치기 전에는 루프 안에서 예외가 나면 건수를 기록하기 전에 빠져나가, 실제로 배치된
 * 워크로드가 있어도 실행 이력에 0 건으로 남았다. 다음 실행이 무엇을 이어서 해야 하는지
 * 알 수 없고, 절감이나 성공률을 세는 어떤 계산도 그 실행을 잘못 읽는다.</p>
 *
 * <p>수집기와 배치 서비스를 대역으로 바꿔 노드는 있게 하고, 두 번째 워크로드에서 예외를
 * 던진다 — 실제 저장소 없이 결정적으로 재현되는 조건이다.</p>
 */
@SpringBootTest
class SchedulePartialRunTest {

    private static final String PREFIX = "prt-";

    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired WorkloadRepository workloadRepository;

    @MockitoBean NodeResourceCollector collector;
    @MockitoBean PlacementService placementService;

    @AfterEach
    void clean() {
        runRepository.findAll().stream()
                .filter(r -> r.getScheduleKey() != null && r.getScheduleKey().startsWith(PREFIX))
                .forEach(runRepository::delete);
        workloadRepository.findAll().stream()
                .filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream()
                .filter(s -> s.getScheduleId().startsWith(PREFIX)).forEach(scheduleRepository::delete);
    }

    @DisplayName("배치 도중 예외가 나도 그때까지의 성공·실패 건수가 이력에 남는다")
    @Test
    void a_run_interrupted_midway_keeps_its_counts() {
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(
                List.of(node("prt-node-1"), node("prt-node-2")), List.of()));

        Workload first = save(PREFIX + "w1");
        Workload second = save(PREFIX + "w2");
        save(PREFIX + "w3");

        // 첫 건은 성공, 두 번째에서 끊는다.
        when(placementService.place(any(Workload.class), any(PlacementContext.class), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    Workload w = invocation.getArgument(0);
                    if (w.getName().equals(second.getName())) {
                        throw new IllegalStateException("배치 도중 끊김");
                    }
                    return new SchedulingResult(true, "mdc-01", "prt-node-1", 0.9,
                            "테스트 배치", List.of());
                });

        Schedule schedule = scheduleRepository.save(scheduleOf(PREFIX + "partial"));

        assertThrows(BusinessException.class, () -> executionService.runNow(schedule.getId(), "tester"));

        ScheduleRun run = runRepository.findAll().stream()
                .filter(r -> PREFIX.concat("partial").equals(r.getScheduleKey()))
                .findFirst().orElseThrow(() -> new AssertionError("실행 이력이 남지 않았다"));

        assertEquals(ScheduleRun.RunStatus.FAILED, run.getStatus());
        assertEquals(3, run.getTargetCount(), "대상 건수는 끊겨도 그대로다");
        assertEquals(1, run.getSuccessCount(), "이미 배치된 건이 이력에서 사라졌다");
        assertEquals(0, run.getFailedCount(), "시도하지 못한 건을 실패로 세면 안 된다");
        assertTrue(first.getName().startsWith(PREFIX));
    }

    private Workload save(String name) {
        Workload workload = new Workload(name, 1, 1024);
        workload.setResourceType(Workload.ResourceType.VM);
        return workloadRepository.save(workload);
    }

    private Schedule scheduleOf(String key) {
        Schedule schedule = new Schedule(key, "부분 집계 " + key, "0 0 2 * * ?");
        schedule.setResourceType(Workload.ResourceType.VM);
        schedule.setEnabled(true);
        schedule.setAutoRun(false);
        return schedule;
    }

    private static NodeResource node(String id) {
        return new NodeResource("mdc-01", id, 16, 65_536, 0, 20, 30, 30, 200, 500, 40, 1_000, 1_000);
    }
}

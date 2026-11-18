package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.PlacementDispatch.DispatchStatus;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 기동 회수 — 실행 도중 프로세스가 죽어 남은 실행 권한을 다시 뜰 때 정리하는가.
 *
 * <p>이것이 유일한 회수 수단이다. 실행 권한은 실행이 끝나며 풀리는데, 그 "끝"에 이르지 못하고
 * 프로세스가 죽으면(= 배포 절차인 재시작) 표시가 남아 그 스케줄은 실행도 삭제도 영구히 거부된다.</p>
 *
 * <p>{@code start()}를 다시 부르지 않고 회수 메서드를 직접 부른다 — 러너 스레드를 늘리지 않기 위해서다.</p>
 */
@SpringBootTest
class ScheduleStartupRecoveryTest {

    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired ScheduleService scheduleService;
    @Autowired PlacementDispatchService dispatchService;
    @Autowired PlacementDispatchRepository dispatchRepository;

    @AfterEach
    void clean() {
        dispatchRepository.findAll().stream()
                .filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith("rec-"))
                .forEach(dispatchRepository::delete);
        historyRepository.findAll().stream()
                .filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith("rec-"))
                .forEach(historyRepository::delete);
        workloadRepository.findAll().stream()
                .filter(w -> w.getName().startsWith("rec-"))
                .forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream()
                .filter(s -> s.getScheduleId().startsWith("rec-"))
                .forEach(s -> {
                    runRepository.deleteAll(runRepository.findByScheduleId(s.getId()));
                    scheduleRepository.delete(s);
                });
    }

    /** 실행 도중 프로세스가 죽은 상태를 만든다 — 권한은 잡혀 있고 예정 시각은 과거다. */
    private Schedule stuck(String id) {
        Schedule schedule = new Schedule(id, id, "0 0 3 * * ?");
        schedule.setEnabled(true);
        schedule.setAutoRun(true);
        schedule.recordRun(ScheduleRun.RunStatus.RUNNING, OffsetDateTime.now().minusHours(2), null, null);
        schedule.setNextRunAt(OffsetDateTime.now().minusHours(2));
        return scheduleRepository.save(schedule);
    }

    private Schedule reload(Schedule schedule) {
        return scheduleRepository.findById(schedule.getId()).orElseThrow();
    }

    /** 중단된 실행이 배치한 워크로드 — 배정 상태로 저장하고 그 실행의 성공 이력에 연결한다. */
    private Workload placedBy(ScheduleRun run, String name) {
        Workload workload = new Workload(name, 2, 1024);
        workload.assignTo("mdc-01", "node-1", 0.8, "배치 성공");
        workload = workloadRepository.save(workload);
        SchedulingHistory history = new SchedulingHistory(workload.getId(), workload.getName(),
                "mdc-01", "node-1", 0.8, "배치 성공", "weighted", "default");
        history.linkToRun(run.getId(), true);
        historyRepository.save(history);
        return workload;
    }

    private ScheduleRun danglingRun(Schedule schedule) {
        return runRepository.save(new ScheduleRun(schedule.getId(), schedule.getScheduleId(),
                ScheduleRun.TriggerType.AUTO, "system"));
    }

    private static SchedulingResult ok() {
        return new SchedulingResult(true, "mdc-01", "node-1", 0.8, "배치 성공", List.of());
    }

    @DisplayName("남아 있던 실행 권한을 회수해 실패로 기록하고 다음 실행 시각을 되살린다")
    @Test
    void recovers_stuck_schedule() {
        Schedule schedule = stuck("rec-basic");

        assertTrue(scheduleRepository.findByLastRunStatus(ScheduleRun.RunStatus.RUNNING).stream()
                .anyMatch(s -> s.getId().equals(schedule.getId())), "회수 전에는 잔류 상태다");

        executionService.recoverStuckRuns();

        Schedule after = reload(schedule);
        assertEquals(ScheduleRun.RunStatus.FAILED, after.getLastRunStatus());
        assertEquals(ScheduleExecutionService.RECOVERED_MESSAGE, after.getLastRunMessage(),
                "왜 실패했는지가 화면에 보여야 한다");
        assertNotNull(after.getLastRunFinishedAt());
        assertNotNull(after.getNextRunAt());
        assertTrue(after.getNextRunAt().isAfter(OffsetDateTime.now()),
                "예정 시각이 과거로 남으면 폴러가 즉시 재실행한다");
    }

    @DisplayName("회수하면 실행도 삭제도 다시 된다 — 영구 잠금이 풀린다")
    @Test
    void recovered_schedule_can_run_and_be_deleted() {
        Schedule schedule = stuck("rec-unlock");

        executionService.recoverStuckRuns();

        assertDoesNotThrow(() -> executionService.runNow(schedule.getId(), "tester"),
                "회수 전에는 이미 실행 중이라며 거부됐다");
        assertDoesNotThrow(() -> scheduleService.delete(List.of(schedule.getId())),
                "회수 전에는 실행 중이라 삭제도 거부됐다");
    }

    @DisplayName("끝나지 않은 실행 이력도 함께 마무리한다 — 화면에 '실행 중'이 영원히 남지 않게")
    @Test
    void finishes_dangling_run_rows() {
        Schedule schedule = stuck("rec-run");
        ScheduleRun dangling = runRepository.save(
                new ScheduleRun(schedule.getId(), schedule.getScheduleId(),
                        ScheduleRun.TriggerType.AUTO, "system"));

        executionService.recoverStuckRuns();

        ScheduleRun after = runRepository.findById(dangling.getId()).orElseThrow();
        assertEquals(ScheduleRun.RunStatus.FAILED, after.getStatus());
        assertEquals(ScheduleExecutionService.RECOVERED_MESSAGE, after.getMessage());
        assertNotNull(after.getFinishedAt());
    }

    @DisplayName("전달 기록이 없는 배치 워크로드는 중단된 실행과 함께 실패로 되돌려 다음 재시도에서 회수한다")
    @Test
    void recovers_workload_placed_by_dangling_run() {
        Schedule schedule = stuck("rec-workload");
        ScheduleRun dangling = runRepository.save(
                new ScheduleRun(schedule.getId(), schedule.getScheduleId(),
                        ScheduleRun.TriggerType.AUTO, "system"));
        Workload workload = new Workload("rec-placed", 2, 1024);
        workload.assignTo("mdc-01", "node-1", 0.8, "배치 성공");
        workload = workloadRepository.save(workload);
        SchedulingHistory history = new SchedulingHistory(workload.getId(), workload.getName(),
                "mdc-01", "node-1", 0.8, "배치 성공", "weighted", "default");
        history.linkToRun(dangling.getId(), true);
        historyRepository.save(history);

        executionService.recoverStuckRuns();

        Workload after = workloadRepository.findById(workload.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.FAILED, after.getStatus());
        assertNull(after.getAssignedMdcId());
        assertNull(after.getAssignedNodeId());
        assertEquals(1, after.resolveRetryCount());
        assertNotNull(after.getLastFailedAt());
        assertEquals(ScheduleExecutionService.RECOVERED_MESSAGE, after.getSchedulingReason());
    }

    @DisplayName("실행 계층이 이미 받은 워크로드는 재시작 뒤에도 배정을 그대로 둔다")
    @Test
    void keeps_workload_already_accepted_by_executor() {
        Schedule schedule = stuck("rec-keep");
        ScheduleRun dangling = danglingRun(schedule);
        Workload workload = placedBy(dangling, "rec-keep-workload");
        PlacementDispatch accepted = dispatchService.afterPlacement(workload, ok(), dangling.getId());
        assertEquals(DispatchStatus.ACCEPTED, accepted.getStatus());

        executionService.recoverStuckRuns();

        Workload after = workloadRepository.findById(workload.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.SCHEDULED, after.getStatus(), "실행 계층이 받은 워크로드를 실패로 돌리면 두 번 배치된다");
        assertEquals("node-1", after.getAssignedNodeId());
        assertEquals(0, after.resolveRetryCount());
        assertEquals(DispatchStatus.ACCEPTED, dispatchRepository.findById(accepted.getId()).orElseThrow().getStatus());
        assertEquals(ScheduleRun.RunStatus.FAILED, runRepository.findById(dangling.getId()).orElseThrow().getStatus(),
                "실행 이력 정리는 전과 같다");
    }

    @DisplayName("전달 중이던 워크로드는 기록을 UNAVAILABLE 로 닫고 실패로 되돌린다")
    @Test
    void closes_requested_dispatch_and_fails_workload() {
        Schedule schedule = stuck("rec-requested");
        ScheduleRun dangling = danglingRun(schedule);
        Workload workload = placedBy(dangling, "rec-requested-workload");
        PlacementDispatch requested = dispatchRepository.saveAndFlush(new PlacementDispatch(workload, dangling.getId(), "SYSTEM"));
        assertEquals(DispatchStatus.REQUESTED, requested.getStatus());

        executionService.recoverStuckRuns();

        PlacementDispatch closed = dispatchRepository.findById(requested.getId()).orElseThrow();
        assertEquals(DispatchStatus.UNAVAILABLE, closed.getStatus());
        assertTrue(closed.getMessage().startsWith(PlacementDispatchService.RESTART_NOTE), closed.getMessage());
        Workload after = workloadRepository.findById(workload.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.FAILED, after.getStatus());
        assertNull(after.getAssignedNodeId());
        assertEquals(1, after.resolveRetryCount());
    }

    @DisplayName("옛 결정의 기록만 있는 워크로드는 전달 기록이 없는 것과 같이 실패로 되돌린다")
    @Test
    void fails_workload_whose_only_dispatch_is_from_an_older_decision() {
        Schedule schedule = stuck("rec-old-row");
        ScheduleRun dangling = danglingRun(schedule);
        Workload workload = placedBy(dangling, "rec-old-row-workload");
        PlacementDispatch old = dispatchService.afterPlacement(workload, ok(), dangling.getId());
        // 같은 워크로드를 다시 배정해 옛 기록보다 확실히 뒤의 결정을 만든다 — 새 기록은 만들지 않는다
        // (옛 기록의 요청 시각은 배정 시각보다 몇 ms 뒤에 찍히므로, 배정 시각이 그보다 확실히 뒤가 될 때까지 반복한다)
        do {
            workload.assignTo("mdc-01", "node-2", 0.6, "재배치");
        } while (!workload.getScheduledAt().isAfter(old.getRequestedAt().plusNanos(2_000_000)));
        workloadRepository.saveAndFlush(workload);

        executionService.recoverStuckRuns();

        Workload after = workloadRepository.findById(workload.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.FAILED, after.getStatus(), "현재 결정의 기록이 없으면 실행 계층이 받은 것이 아니다");
        assertNull(after.getAssignedNodeId());
        assertEquals(DispatchStatus.ACCEPTED, dispatchRepository.findById(old.getId()).orElseThrow().getStatus(), "옛 결정의 기록은 손대지 않는다");
    }

    @DisplayName("중단된 실행과 관계없는 배치 워크로드는 기동 회수가 건드리지 않는다")
    @Test
    void leaves_unrelated_scheduled_workload_alone() {
        stuck("rec-unrelated-run");
        Workload unrelated = new Workload("rec-unrelated-workload", 2, 1024);
        unrelated.assignTo("mdc-02", "node-2", 0.7, "다른 실행의 배치");
        unrelated = workloadRepository.save(unrelated);

        executionService.recoverStuckRuns();

        Workload after = workloadRepository.findById(unrelated.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.SCHEDULED, after.getStatus());
        assertEquals("node-2", after.getAssignedNodeId());
        assertEquals(0, after.resolveRetryCount());
    }

    @DisplayName("실행 중이 아닌 스케줄은 건드리지 않는다 — 꺼진 사이 지난 예정을 몰아 돌리지 않는 계약 유지")
    @Test
    void leaves_healthy_schedule_alone() {
        Schedule healthy = new Schedule("rec-healthy", "rec-healthy", "0 0 3 * * ?");
        healthy.setEnabled(true);
        healthy.setAutoRun(true);
        OffsetDateTime overdue = OffsetDateTime.now().minusHours(5);
        healthy.setNextRunAt(overdue);
        scheduleRepository.save(healthy);

        executionService.recoverStuckRuns();

        Schedule after = reload(healthy);
        // DB는 나노초를 마이크로초로 잘라 저장하므로 밀리초까지만 비교한다.
        assertEquals(overdue.toInstant().toEpochMilli(), after.getNextRunAt().toInstant().toEpochMilli(),
                "연체된 예정 시각을 회수가 밀어버리면 지난 예정 1회 실행 계약이 깨진다");
        assertNull(after.getLastRunStatus());
    }

    @DisplayName("회수 대상이 아닌 스케줄은 회수 뒤에도 그대로다")
    @Test
    void non_stuck_schedule_is_untouched() {
        Schedule normal = new Schedule("rec-normal", "rec-normal", "0 0 3 * * ?");
        normal.setEnabled(true);
        normal.setAutoRun(true);
        normal.recordRun(ScheduleRun.RunStatus.COMPLETED, OffsetDateTime.now().minusHours(1),
                OffsetDateTime.now().minusHours(1), "정상 종료");
        scheduleRepository.save(normal);

        executionService.recoverStuckRuns();

        Schedule after = reload(normal);
        assertEquals(ScheduleRun.RunStatus.COMPLETED, after.getLastRunStatus(), "완료된 실행을 실패로 바꾸면 안 된다");
        assertEquals("정상 종료", after.getLastRunMessage());
    }
}

package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 기동 회수 — 실행 도중 프로세스가 죽어 남은 실행 권한을 다시 뜰 때 정리하는가.
 *
 * <p>이것이 유일한 회수 수단이다. 실행 권한은 실행이 끝나며 풀리는데, 그 "끝"에 도달하지 못하고
 * 프로세스가 죽으면(= 배포 절차인 재시작) 표시가 남아 그 스케줄은 실행도 삭제도 영구히 거부된다.</p>
 *
 * <p>{@code start()} 를 다시 부르지 않고 회수 메서드를 직접 부른다 — 러너 스레드를 늘리지 않기 위해서다.</p>
 */
@SpringBootTest
class ScheduleStartupRecoveryTest {

    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired ScheduleService scheduleService;

    @AfterEach
    void clean() {
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

    @DisplayName("남아 있던 실행 권한을 회수해 실패로 기록하고 다음 실행 시각을 되살린다")
    @Test
    void recovers_stuck_schedule() {
        Schedule schedule = stuck("rec-basic");

        assertTrue(executionService.recoverStuckRuns() >= 1);

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
        // DB 는 나노초를 마이크로초로 잘라 저장하므로 밀리초까지만 비교한다.
        assertEquals(overdue.toInstant().toEpochMilli(), after.getNextRunAt().toInstant().toEpochMilli(),
                "연체된 예정 시각을 회수가 밀어버리면 지난 예정 1회 실행 계약이 깨진다");
        assertNull(after.getLastRunStatus());
    }

    @DisplayName("회수할 것이 없으면 아무 일도 하지 않는다")
    @Test
    void no_stuck_schedule_is_a_noop() {
        assertEquals(0, executionService.recoverStuckRuns());
    }
}

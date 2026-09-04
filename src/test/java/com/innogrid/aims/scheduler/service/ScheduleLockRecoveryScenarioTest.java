package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 시나리오 — 배포 도중 중단된 스케줄이 재기동으로 되살아나는 전 과정.
 *
 * <pre>
 * 배경   실행 권한은 실행이 끝나며 풀린다. 그런데 배포 절차가 곧 앱 재시작이라, 실행 도중 배포하면
 *        "실행 중" 표시가 그대로 남는다. 지금까지는 그 스케줄이 실행도 삭제도 영구히 거부됐다.
 * 절차   ① 실행 중 배포로 중단된 상태를 만든다  ② 그 상태에서는 실행·삭제가 모두 막힌다
 *        ③ 앱이 다시 뜨며 회수한다  ④ 실패로 기록되고 사유가 남는다
 *        ⑤ 실행도 삭제도 다시 되고, 폴러가 즉시 재실행하지도 않는다
 * </pre>
 */
@SpringBootTest
class ScheduleLockRecoveryScenarioTest {

    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;

    @AfterEach
    void clean() {
        scheduleRepository.findAll().stream()
                .filter(s -> s.getScheduleId().startsWith("scn-"))
                .forEach(s -> {
                    runRepository.deleteAll(runRepository.findByScheduleId(s.getId()));
                    scheduleRepository.delete(s);
                });
    }

    @DisplayName("배포로 중단된 스케줄이 재기동으로 되살아난다 — 잠김 확인부터 원복까지")
    @Test
    void stuck_by_deploy_then_recovered_on_restart() {
        // ① 실행 중 배포로 중단된 상태 — 권한은 잡혀 있고 실행 이력은 끝나지 않았다
        Schedule schedule = new Schedule("scn-deploy", "배포 중 중단", "0 0 3 * * ?");
        schedule.setEnabled(true);
        schedule.setAutoRun(true);
        schedule.recordRun(ScheduleRun.RunStatus.RUNNING, OffsetDateTime.now().minusMinutes(3), null, null);
        schedule.setNextRunAt(OffsetDateTime.now().minusMinutes(3));
        scheduleRepository.save(schedule);
        ScheduleRun dangling = runRepository.save(new ScheduleRun(
                schedule.getId(), schedule.getScheduleId(), ScheduleRun.TriggerType.AUTO, "system"));

        // ② 이 상태에서는 실행도 삭제도 막힌다
        assertEquals(409, assertThrows(BusinessException.class,
                () -> executionService.runNow(schedule.getId(), "tester")).getStatus().value(),
                "이미 실행 중이라며 거부된다");
        assertEquals(409, assertThrows(BusinessException.class,
                () -> scheduleService.delete(List.of(schedule.getId()))).getStatus().value(),
                "지울 수도 없다 — 여기서 막히면 푸는 길이 psql 뿐이었다");

        // ③ 앱이 다시 뜨며 회수한다
        assertTrue(executionService.recoverStuckRuns() >= 1);

        // ④ 실패로 기록되고 사유가 남는다 — 스케줄과 실행 이력 양쪽에
        Schedule after = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertEquals(ScheduleRun.RunStatus.FAILED, after.getLastRunStatus());
        assertEquals(ScheduleExecutionService.RECOVERED_MESSAGE, after.getLastRunMessage());

        ScheduleRun run = runRepository.findById(dangling.getId()).orElseThrow();
        assertEquals(ScheduleRun.RunStatus.FAILED, run.getStatus());
        assertEquals(ScheduleExecutionService.RECOVERED_MESSAGE, run.getMessage());
        assertNotNull(run.getFinishedAt(), "화면에 '실행 중'이 영원히 남으면 안 된다");

        // ⑤ 다시 실행할 수 있고, 폴러가 즉시 재실행하지도 않는다
        assertTrue(after.getNextRunAt().isAfter(OffsetDateTime.now()),
                "예정 시각이 과거로 남으면 폴러가 주기마다 재제출한다");
        assertFalse(scheduleRepository.findDueForRun(OffsetDateTime.now(), ScheduleRun.RunStatus.RUNNING)
                        .stream().anyMatch(s -> s.getId().equals(schedule.getId())),
                "회수 직후 곧바로 자동 실행 대상이 되면 안 된다");
        assertDoesNotThrow(() -> executionService.runNow(schedule.getId(), "tester"));
        assertDoesNotThrow(() -> scheduleService.delete(List.of(schedule.getId())));
    }

    @DisplayName("정상 실행은 끝나며 스스로 권한을 풀고 다음 예정으로 넘어간다 — 회수가 필요 없다")
    @Test
    void healthy_run_releases_itself() {
        Schedule schedule = new Schedule("scn-healthy", "정상", "0 0 3 * * ?");
        schedule.setEnabled(true);
        schedule.setAutoRun(true);
        scheduleService.refreshNextRunAt(schedule);
        scheduleRepository.save(schedule);

        executionService.runNow(schedule.getId(), "tester");

        Schedule after = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertNotEquals(ScheduleRun.RunStatus.RUNNING, after.getLastRunStatus());
        assertTrue(after.getNextRunAt().isAfter(OffsetDateTime.now()));
        executionService.recoverStuckRuns();
        assertEquals(ScheduleRun.RunStatus.COMPLETED,
                scheduleRepository.findById(schedule.getId()).orElseThrow().getLastRunStatus(),
                "회수가 정상 종료된 실행을 건드리면 안 된다");
    }
}

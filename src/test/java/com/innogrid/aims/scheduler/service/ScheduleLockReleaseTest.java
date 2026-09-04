package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;

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
 * 실행 권한(락)이 <b>어떤 경우에도</b> 풀리는가.
 *
 * <p>권한은 실행 시작 시 DB 에 남고 마무리에서 풀린다. 그런데 마무리가 실패하면 권한이 남아
 * 그 스케줄은 자동·수동 실행도 삭제도 영구히 거부된다 — 푸는 길이 psql 뿐이었다.</p>
 *
 * <p><b>이 클래스는 {@code @Transactional} 을 쓰지 않는다.</b> 권한 해제가 {@code REQUIRES_NEW} 로 도는데,
 * 테스트가 트랜잭션 안이면 그 트랜잭션이 잡은 행 락을 새 트랜잭션이 기다려 교착한다.</p>
 */
@SpringBootTest
class ScheduleLockReleaseTest {

    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired ScheduleService scheduleService;

    @AfterEach
    void clean() {
        scheduleRepository.findAll().stream()
                .filter(s -> s.getScheduleId().startsWith("lock-"))
                .forEach(s -> {
                    runRepository.deleteAll(runRepository.findByScheduleId(s.getId()));
                    scheduleRepository.delete(s);
                });
    }

    private Schedule save(String id, String cron) {
        Schedule schedule = new Schedule(id, id, cron);
        schedule.setEnabled(true);
        schedule.setAutoRun(true);
        scheduleService.refreshNextRunAt(schedule);
        return scheduleRepository.save(schedule);
    }

    private Schedule reload(Schedule schedule) {
        return scheduleRepository.findById(schedule.getId()).orElseThrow();
    }

    // ── 메시지 길이 ────────────────────────────────────────────

    @DisplayName("실행 결과 메시지는 컬럼 길이로 잘린다 — 길다고 상태 기록이 통째로 실패하면 안 된다")
    @Test
    void long_message_is_trimmed() {
        String tooLong = "가".repeat(ScheduleExecutionService.MESSAGE_MAX + 200);

        String trimmed = ScheduleExecutionService.trim(tooLong);

        assertEquals(ScheduleExecutionService.MESSAGE_MAX, trimmed.length());
        assertTrue(trimmed.endsWith("..."));
        assertNull(ScheduleExecutionService.trim(null));
        assertEquals("짧다", ScheduleExecutionService.trim("짧다"), "짧은 메시지는 그대로 둔다");
    }

    @DisplayName("긴 실패 메시지로 실행이 끝나도 권한이 풀리고 다시 실행할 수 있다")
    @Test
    void long_failure_message_still_releases_lock() {
        Schedule schedule = save("lock-long", "0 0 3 * * ?");

        // 대상 워크로드가 없어 즉시 끝나는 실행이지만, 상태 기록 경로는 동일하다.
        executionService.runNow(schedule.getId(), "tester");

        Schedule after = reload(schedule);
        assertNotEquals(ScheduleRun.RunStatus.RUNNING, after.getLastRunStatus());
        assertDoesNotThrow(() -> executionService.runNow(schedule.getId(), "tester"),
                "권한이 풀렸으므로 다시 실행할 수 있다");
    }

    // ── 권한 해제가 다음 실행 시각도 민다 ──────────────────────

    @DisplayName("실행이 끝나면 다음 실행 시각이 미래로 밀린다 — 폴러가 같은 실행을 즉시 다시 던지지 않게")
    @Test
    void completion_advances_next_run_at() {
        Schedule schedule = save("lock-next", "0 0 3 * * ?");
        scheduleRepository.save(schedule);

        executionService.runNow(schedule.getId(), "tester");

        Schedule after = reload(schedule);
        assertNotNull(after.getNextRunAt());
        assertTrue(after.getNextRunAt().isAfter(OffsetDateTime.now()),
                "다음 실행 시각이 과거면 폴러가 매 주기마다 재제출한다: " + after.getNextRunAt());
    }

    @DisplayName("실행 중 스케줄을 꺼도 실행이 끝나며 되살아나지 않는다")
    @Test
    void disable_during_run_is_not_reverted() {
        Schedule schedule = save("lock-disable", "0 0 3 * * ?");

        // 폴러가 읽어 워커로 넘긴 상황을 흉내낸다 — 이 사본은 enabled=true 인 낡은 스냅샷이다.
        Schedule detached = reload(schedule);

        // 그 사이 사용자가 스케줄을 끈다.
        Schedule current = reload(schedule);
        current.setEnabled(false);
        scheduleRepository.save(current);

        // 낡은 사본으로 실행 결과를 기록해도 enabled 가 되살아나면 안 된다.
        executionService.complete(detached, ScheduleRun.RunStatus.COMPLETED,
                OffsetDateTime.now(), "테스트");

        assertFalse(reload(schedule).isEnabled(),
                "엔티티 통째 저장이면 enabled 가 true 로 되돌아간다");
    }
}

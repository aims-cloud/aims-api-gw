package com.innogrid.aims.scheduler.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 폴러가 쓰는 자동 실행 대상 조회.
 *
 * <p>러너는 기동 후 30초가 지나야 첫 폴을 하는데 테스트는 그 전에 끝나므로, 러너를 거쳐서는 조건이 잘못돼도
 * 잡지 못한다. 그래서 이 조회를 직접 부른다.</p>
 *
 * <p>특히 <b>NULL 함정</b>을 고정한다 — {@code last_run_status}는 nullable이라 파생 쿼리
 * {@code …LastRunStatusNot(RUNNING)}으로 쓰면 SQL이 {@code <> 'RUNNING'}이 되어
 * <b>한 번도 실행된 적 없는 스케줄이 전부 탈락</b>한다. 새로 만든 스케줄이 영영 자동 실행되지 않는 결함이다.</p>
 */
@SpringBootTest
class ScheduleDueQueryTest {

    @Autowired ScheduleRepository scheduleRepository;

    @AfterEach
    void clean() {
        scheduleRepository.findAll().stream()
                .filter(s -> s.getScheduleId().startsWith("due-"))
                .forEach(scheduleRepository::delete);
    }

    private Schedule save(String id, boolean enabled, boolean autoRun,
                          OffsetDateTime nextRunAt, ScheduleRun.RunStatus lastStatus) {
        Schedule schedule = new Schedule(id, id, "0 0 3 * * ?");
        schedule.setEnabled(enabled);
        schedule.setAutoRun(autoRun);
        schedule.setNextRunAt(nextRunAt);
        if (lastStatus != null) {
            schedule.recordRun(lastStatus, OffsetDateTime.now().minusMinutes(1), null, null);
        }
        return scheduleRepository.save(schedule);
    }

    private List<String> due() {
        return scheduleRepository.findDueForRun(OffsetDateTime.now(), ScheduleRun.RunStatus.RUNNING)
                .stream().map(Schedule::getScheduleId).filter(id -> id.startsWith("due-")).toList();
    }

    @DisplayName("한 번도 실행된 적 없는 스케줄이 대상에 들어온다 — NULL 상태를 빠뜨리면 자동 실행이 영영 안 된다")
    @Test
    void never_run_schedule_is_due() {
        save("due-fresh", true, true, OffsetDateTime.now().minusMinutes(1), null);

        assertTrue(due().contains("due-fresh"),
                "last_run_status 가 NULL 인 스케줄이 빠지면 새 스케줄은 자동 실행되지 않는다");
    }

    @DisplayName("실행이 끝난 스케줄은 다시 대상이 된다")
    @Test
    void completed_schedule_is_due_again() {
        save("due-done", true, true, OffsetDateTime.now().minusMinutes(1), ScheduleRun.RunStatus.COMPLETED);
        save("due-failed", true, true, OffsetDateTime.now().minusMinutes(1), ScheduleRun.RunStatus.FAILED);

        assertTrue(due().containsAll(List.of("due-done", "due-failed")));
    }

    @DisplayName("이미 실행 중인 스케줄은 대상에서 빠진다 — 한 틱에 두 번 실행되지 않게")
    @Test
    void running_schedule_is_not_due() {
        save("due-running", true, true, OffsetDateTime.now().minusMinutes(1), ScheduleRun.RunStatus.RUNNING);

        assertFalse(due().contains("due-running"),
                "실행 중인데도 대상에 들어오면 폴링 주기마다 같은 실행이 큐에 쌓인다");
    }

    @DisplayName("끄거나 자동 실행이 아니거나 예정 시각이 미래면 대상이 아니다")
    @Test
    void gates_are_kept() {
        save("due-off", false, true, OffsetDateTime.now().minusMinutes(1), null);
        save("due-manual", true, false, OffsetDateTime.now().minusMinutes(1), null);
        save("due-future", true, true, OffsetDateTime.now().plusHours(1), null);

        List<String> due = due();
        assertFalse(due.contains("due-off"));
        assertFalse(due.contains("due-manual"));
        assertFalse(due.contains("due-future"));
    }

    @DisplayName("예정 시각이 이른 것부터 나온다")
    @Test
    void ordered_by_next_run_at() {
        save("due-late", true, true, OffsetDateTime.now().minusMinutes(1), null);
        save("due-early", true, true, OffsetDateTime.now().minusHours(3), null);

        assertEquals(List.of("due-early", "due-late"), due());
    }
}

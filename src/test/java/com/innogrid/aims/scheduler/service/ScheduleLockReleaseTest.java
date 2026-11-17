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
 * <p>권한은 실행 시작 시 DB에 남고 마무리에서 풀린다. 그런데 마무리가 실패하면 권한이 남아
 * 그 스케줄은 자동·수동 실행도 삭제도 영구히 거부된다 — 푸는 길이 psql뿐이었다.</p>
 *
 * <p><b>이 클래스는 {@code @Transactional}을 쓰지 않는다.</b> 실행 서비스는 운영에서 트랜잭션 밖에서 도는데,
 * 테스트가 트랜잭션으로 감싸면 커밋되지 않은 상태를 보게 되어 "정말 풀렸는가"를 확인할 수 없다.
 * 여기서는 DB에 실제로 반영된 값을 다시 읽어 확인한다.</p>
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

    // ── 긴 메시지가 상태 기록을 깨뜨리지 않는다 ─────────────────

    /**
     * 실패 사유에는 제외 노드 목록·첫 실패 사유가 붙어 컬럼 길이(500자)를 넘길 수 있다.
     * 자르지 않으면 저장이 통째로 실패해 <b>실행 권한이 안 풀린다.</b>
     */
    @DisplayName("500자를 넘는 메시지로 마무리해도 DB 에 저장되고 실행 권한이 풀린다")
    @Test
    void long_message_is_persisted_and_lock_released() {
        Schedule schedule = save("lock-long", "0 0 3 * * ?");
        String tooLong = "제외 노드 ".repeat(300);   // 1,800자
        assertTrue(tooLong.length() > ScheduleRun.MESSAGE_MAX);

        executionService.complete(schedule, ScheduleRun.RunStatus.FAILED, OffsetDateTime.now(), tooLong);

        Schedule after = reload(schedule);
        assertEquals(ScheduleRun.MESSAGE_MAX, after.getLastRunMessage().length(),
                "컬럼 길이에 맞춰 잘려서 저장돼야 한다 — 안 자르면 저장 자체가 실패한다");
        assertTrue(after.getLastRunMessage().endsWith("..."), "잘렸다는 것이 보여야 한다");
        assertEquals(ScheduleRun.RunStatus.FAILED, after.getLastRunStatus());
        assertDoesNotThrow(() -> executionService.runNow(schedule.getId(), "tester"),
                "권한이 풀렸으므로 다시 실행할 수 있다");
    }

    @DisplayName("실행 이력의 메시지도 컬럼 길이로 잘린다 — 성공한 실행이 실패로 뒤바뀌지 않게")
    @Test
    void run_message_is_trimmed_on_save() {
        Schedule schedule = save("lock-runmsg", "0 0 3 * * ?");
        ScheduleRun run = runRepository.save(new ScheduleRun(
                schedule.getId(), schedule.getScheduleId(), ScheduleRun.TriggerType.MANUAL, "tester"));

        run.finish(ScheduleRun.RunStatus.COMPLETED, "사유 ".repeat(400));   // 1,200자
        ScheduleRun saved = runRepository.save(run);

        ScheduleRun after = runRepository.findById(saved.getId()).orElseThrow();
        assertEquals(ScheduleRun.MESSAGE_MAX, after.getMessage().length());
        assertEquals(ScheduleRun.RunStatus.COMPLETED, after.getStatus(),
                "저장이 터지면 성공한 실행이 실패로 기록된다");
    }

    // ── 다음 실행 시각 ─────────────────────────────────────────

    @DisplayName("실행이 끝나면 다음 실행 시각이 미래로 밀린다 — 폴러가 같은 실행을 즉시 다시 던지지 않게")
    @Test
    void completion_advances_next_run_at() {
        Schedule schedule = save("lock-next", "0 0 3 * * ?");

        executionService.runNow(schedule.getId(), "tester");

        Schedule after = reload(schedule);
        assertNotNull(after.getNextRunAt());
        assertTrue(after.getNextRunAt().isAfter(OffsetDateTime.now()),
                "다음 실행 시각이 과거면 폴러가 매 주기마다 재제출한다: " + after.getNextRunAt());
    }

    /**
     * cron을 읽을 수 없어도 <b>권한 해제를 막지 않는다.</b> 다만 폴 주기만큼만 밀면
     * "실행 → 실패 → 주기 뒤 재실행"이 끝없이 도는 핫 루프가 되므로 넉넉히 민다.
     */
    @DisplayName("cron 을 읽을 수 없어도 권한이 풀리고, 다음 실행 시각은 폴 주기가 아니라 넉넉히 밀린다")
    @Test
    void broken_cron_still_releases_lock_without_hot_loop() {
        Schedule schedule = save("lock-cron", "0 0 3 * * ?");
        // 검증을 우회해 DB에 깨진 cron을 심는다 — 수동 수정·라이브러리 변화로 실제 가능한 상태다.
        Schedule broken = reload(schedule);
        broken.setCronExpression("이건 cron 이 아니다");
        scheduleRepository.save(broken);

        OffsetDateTime before = OffsetDateTime.now();
        executionService.complete(reload(schedule), ScheduleRun.RunStatus.FAILED, before, "실패");

        Schedule after = reload(schedule);
        assertNotEquals(ScheduleRun.RunStatus.RUNNING, after.getLastRunStatus(), "권한은 풀려야 한다");
        assertNotNull(after.getNextRunAt());
        assertTrue(after.getNextRunAt().isAfter(before.plusMinutes(ScheduleExecutionService.CRON_FALLBACK_MINUTES - 1)),
                "폴 주기(10초)만큼만 밀면 매 주기 재실행되는 핫 루프가 된다: " + after.getNextRunAt());
    }

    // ── 실행 중 편집이 되돌려지지 않는다 ────────────────────────

    @DisplayName("실행 중 스케줄을 꺼도 실행이 끝나며 되살아나지 않는다")
    @Test
    void disable_during_run_is_not_reverted() {
        Schedule schedule = save("lock-disable", "0 0 3 * * ?");
        Schedule detached = reload(schedule);   // 폴러가 워커로 넘긴 낡은 사본

        Schedule current = reload(schedule);
        current.setEnabled(false);
        current.setName("사용자가 바꾼 이름");
        scheduleRepository.save(current);

        executionService.complete(detached, ScheduleRun.RunStatus.COMPLETED, OffsetDateTime.now(), "테스트");

        Schedule after = reload(schedule);
        assertFalse(after.isEnabled(), "엔티티 통째 저장이면 enabled 가 true 로 되돌아간다");
        assertEquals("사용자가 바꾼 이름", after.getName(), "이름도 되돌아가면 안 된다");
    }

    /**
     * 실행 중에 자동 실행을 켜면, 낡은 사본으로 계산한 {@code nextRunAt = null}이 그 값을 덮어
     * <b>그 스케줄만 조용히 자동 실행되지 않게</b> 된다(폴러는 null을 건너뛴다).
     */
    @DisplayName("실행 중 자동 실행을 켜면 다음 실행 시각이 살아남는다 — 낡은 사본으로 지우면 안 된다")
    @Test
    void enabling_auto_run_during_run_is_not_erased() {
        Schedule schedule = new Schedule("lock-enable", "lock-enable", "0 0 3 * * ?");
        schedule.setEnabled(true);
        schedule.setAutoRun(false);            // 자동 실행 꺼진 채 시작
        scheduleRepository.save(schedule);
        Schedule detached = reload(schedule);  // 이 사본은 autoRun=false다

        Schedule current = reload(schedule);   // 그 사이 사용자가 자동 실행을 켠다
        current.setAutoRun(true);
        scheduleService.refreshNextRunAt(current);
        scheduleRepository.save(current);

        executionService.complete(detached, ScheduleRun.RunStatus.COMPLETED, OffsetDateTime.now(), "테스트");

        Schedule after = reload(schedule);
        assertTrue(after.isAutoRun());
        assertNotNull(after.getNextRunAt(),
                "낡은 사본으로 계산하면 null 이 되어 이 스케줄만 영영 자동 실행되지 않는다");
    }
}

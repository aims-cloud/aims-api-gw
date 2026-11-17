package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.innogrid.aims.scheduler.dto.ScheduleUpdateRequest;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실행과 겹친 스케줄 편집이 최근 실행 네 칸(실행 상태·시작·종료·메시지)을 되돌리지 않는가.
 *
 * <p>편집은 엔티티를 읽어 바꾼 칸만 세터로 반영하고 저장한다. 엔티티가 모든 칸을 UPDATE 하면 편집이 읽은 시점의
 * 네 칸 값이 실행 계층의 전용 UPDATE({@code acquireForRun}·{@code recordRunResult})를 덮어, 실행 권한이 풀리거나
 * 끝난 실행이 실행 중으로 되살아난다. 운영의 경합 창은 편집 트랜잭션 길이(ms)라 스레드로는 재현할 수 없으므로
 * 여기서는 트랜잭션을 겹쳐 결정적으로 만든다 — 바깥 트랜잭션(REQUIRED) 안에서 스케줄을 읽어 1차 캐시에 올리고,
 * 안쪽 별도 트랜잭션(REQUIRES_NEW)으로 실행 쪽 UPDATE를 커밋한 뒤, 같은 바깥 트랜잭션에서 편집을 부르면 편집은
 * 낡은 엔티티를 그대로 쓴다. 안쪽을 REQUIRED로 바꾸면 실행 쪽 UPDATE가 따로 커밋되지 않고, {@code acquireForRun}을
 * 쓰는 두 테스트는 그 {@code clearAutomatically}가 1차 캐시를 비워 재현되지 않는다 — 별도 트랜잭션이 전제다.</p>
 *
 * <p><b>이 클래스는 {@code @Transactional}을 쓰지 않는다.</b> 커밋된 값을 다시 읽어 확인한다. 반대 방향(실행 쪽
 * 사본이 편집을 되돌리지 않는가)은 {@link ScheduleLockReleaseTest}가 본다.</p>
 */
@SpringBootTest
class ScheduleEditDuringRunTest {

    private static final String CRON_A = "0 0 3 * * ?";
    private static final String CRON_B = "0 30 3 * * ?";

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper objectMapper;

    @AfterEach
    void clean() {
        scheduleRepository.findAll().stream()
                .filter(s -> s.getScheduleId().startsWith("edit-run-"))
                .forEach(s -> {
                    runRepository.deleteAll(runRepository.findByScheduleId(s.getId()));
                    scheduleRepository.delete(s);
                });
    }

    private TransactionTemplate outer() {
        return new TransactionTemplate(transactionManager);
    }

    private TransactionTemplate inner() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    private Schedule save(String id, boolean autoRun) {
        Schedule schedule = new Schedule(id, id, CRON_A);
        schedule.setEnabled(true);
        schedule.setAutoRun(autoRun);
        scheduleService.refreshNextRunAt(schedule);
        return scheduleRepository.saveAndFlush(schedule);
    }

    private Schedule reload(Long id) {
        return scheduleRepository.findById(id).orElseThrow();
    }

    private static ScheduleUpdateRequest nameOnly(String name) {
        return new ScheduleUpdateRequest(null, name, null, null, null, null, null, null, null, null, null, null);
    }

    private static ScheduleUpdateRequest cronOnly(String cron) {
        return new ScheduleUpdateRequest(null, null, null, cron, null, null, null, null, null, null, null, null);
    }

    /** 편집이 읽은 뒤 실행 쪽 UPDATE가 커밋되고, 같은 트랜잭션에서 편집이 저장되는 운영의 겹침을 그대로 만든다. */
    private void editAfter(Long id, Runnable runSideUpdate, ScheduleUpdateRequest request) {
        outer().executeWithoutResult(status -> {
            scheduleRepository.findById(id).orElseThrow();          // 편집이 읽은 시점의 사본을 1차 캐시에 올린다
            inner().executeWithoutResult(s -> runSideUpdate.run());  // 그 사이 실행 쪽이 커밋한다
            scheduleService.update(id, request);                     // REQUIRED로 합류 → 낡은 사본으로 저장
        });
    }

    @DisplayName("편집이 스케줄을 읽은 뒤 실행이 시작돼도 저장한 편집이 실행 중 표시를 지우지 않는다")
    @Test
    void edit_committed_after_run_start_keeps_running_flag() {
        Schedule schedule = save("edit-run-start", false);
        OffsetDateTime startedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        OffsetDateTime before = reload(schedule.getId()).getUpdatedAt();

        editAfter(schedule.getId(),
                () -> assertEquals(1, scheduleRepository.acquireForRun(schedule.getId(), ScheduleRun.RunStatus.RUNNING, startedAt)),
                nameOnly("이름 바꿈"));

        Schedule after = reload(schedule.getId());
        assertEquals(ScheduleRun.RunStatus.RUNNING, after.getLastRunStatus(), "편집이 실행 권한을 지우면 안 된다");
        assertEquals(startedAt.toInstant(), after.getLastRunAt().toInstant());
        assertNull(after.getLastRunFinishedAt());
        assertEquals("이름 바꿈", after.getName(), "편집이 바꾼 칸은 저장된다");
        assertEquals(ScheduleActorResolver.SYSTEM, after.getUpdatedBy());
        assertTrue(!after.getUpdatedAt().isBefore(before), "수정 시각이 편집 전보다 앞서지 않는다");
        assertEquals(0, scheduleRepository.acquireForRun(schedule.getId(), ScheduleRun.RunStatus.RUNNING, OffsetDateTime.now()),
                "실행 권한이 남아 있어 두 번째 실행은 잡지 못한다");
    }

    @DisplayName("편집이 스케줄을 읽은 뒤 실행이 끝나도 저장한 편집이 실행 중 표시를 되살리지 않는다")
    @Test
    void edit_committed_after_run_finish_does_not_restore_running() {
        Schedule schedule = save("edit-run-finish", false);
        OffsetDateTime startedAt = OffsetDateTime.now().minusSeconds(5).truncatedTo(ChronoUnit.MILLIS);
        OffsetDateTime finishedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        assertEquals(1, scheduleRepository.acquireForRun(schedule.getId(), ScheduleRun.RunStatus.RUNNING, startedAt));

        editAfter(schedule.getId(),
                () -> assertEquals(1, scheduleRepository.recordRunResult(schedule.getId(), ScheduleRun.RunStatus.COMPLETED,
                        startedAt, finishedAt, "대상 없음", null)),
                objectMapper.readValue("{\"description\":\"설명 바꿈\"}", ScheduleUpdateRequest.class));

        Schedule after = reload(schedule.getId());
        assertEquals(ScheduleRun.RunStatus.COMPLETED, after.getLastRunStatus(), "끝난 실행이 실행 중으로 되살아나면 안 된다");
        assertEquals(finishedAt.toInstant(), after.getLastRunFinishedAt().toInstant());
        assertEquals("대상 없음", after.getLastRunMessage());
        assertEquals("설명 바꿈", after.getDescription());
        assertEquals(1, scheduleRepository.acquireForRun(schedule.getId(), ScheduleRun.RunStatus.RUNNING, OffsetDateTime.now()),
                "실행 권한이 풀려 있어 다음 실행을 잡을 수 있다");
    }

    @DisplayName("실행 중에 실행 주기를 바꾸면 다음 실행 시각은 새 주기로 바뀌고 실행 중 표시는 남는다")
    @Test
    void cron_edit_during_run_updates_next_run_at_and_keeps_running_flag() {
        Schedule schedule = save("edit-run-cron", true);
        OffsetDateTime previousNext = reload(schedule.getId()).getNextRunAt();
        assertNotNull(previousNext);

        editAfter(schedule.getId(),
                () -> assertEquals(1, scheduleRepository.acquireForRun(schedule.getId(), ScheduleRun.RunStatus.RUNNING, OffsetDateTime.now())),
                cronOnly(CRON_B));

        Schedule after = reload(schedule.getId());
        assertEquals(ScheduleRun.RunStatus.RUNNING, after.getLastRunStatus());
        assertEquals(CRON_B, after.getCronExpression());
        assertNotEquals(previousNext.toInstant(), after.getNextRunAt().toInstant(), "편집이 바꾼 칸(다음 실행 시각)은 여전히 쓰인다");
        assertEquals(scheduleService.nextRunAt(CRON_B, true).toInstant(), after.getNextRunAt().toInstant());
    }
}

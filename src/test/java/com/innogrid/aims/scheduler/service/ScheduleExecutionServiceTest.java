package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 테스트 환경에는 메트릭 저장소가 없어 노드 수집 결과가 항상 비어 있다.
 * 그래서 "대상이 있으면 활성 노드 없음으로 실패", "대상이 없으면 대상 없음으로 완료"가
 * 결정적으로 재현된다. 실행 판정 규칙을 확인하기에는 충분하다.
 */
@SpringBootTest
@Transactional
class ScheduleExecutionServiceTest {

    @Autowired private ScheduleExecutionService executionService;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private ScheduleRunRepository runRepository;
    @Autowired private WorkloadRepository workloadRepository;
    @Autowired private SchedulerProperties properties;
    @Autowired private SchedulingService schedulingService;

    @BeforeEach
    void clean() {
        runRepository.deleteAll();
        scheduleRepository.deleteAll();
        workloadRepository.deleteAll();
    }

    // ── 게이트 ─────────────────────────────────────────────────

    @DisplayName("사용하지 않는 스케줄은 실행할 수 없다")
    @Test
    void disabled_schedule_cannot_run() {
        Schedule schedule = save(scheduleOf("off", false, false));

        BusinessException e = assertThrows(BusinessException.class,
                () -> executionService.runNow(schedule.getId(), "tester"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    @DisplayName("워크로드가 없어도 실행 자체는 막지 않는다")
    @Test
    void no_workloads_does_not_block_run() {
        Schedule schedule = save(scheduleOf("empty", true, false));

        assertDoesNotThrow(() -> executionService.runNow(schedule.getId(), "tester"));
    }

    @DisplayName("이미 실행 중이면 409 이다")
    @Test
    void already_running_409() {
        Schedule schedule = scheduleOf("busy", true, false);
        schedule.recordRun(ScheduleRun.RunStatus.RUNNING, null, null, null);
        Schedule saved = save(schedule);

        BusinessException e = assertThrows(BusinessException.class,
                () -> executionService.runNow(saved.getId(), "tester"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    // ── 대상 0건 처리 ──────────────────────────────────────────

    @DisplayName("수동 실행은 대상이 없어도 이력을 남긴다")
    @Test
    void manual_run_records_empty_history() {
        Schedule schedule = save(scheduleOf("manual", true, false));

        ScheduleRun run = executionService.runNow(schedule.getId(), "tester");

        assertNotNull(run);
        assertEquals(ScheduleRun.TriggerType.MANUAL, run.getTriggerType());
        assertEquals(ScheduleRun.RunStatus.COMPLETED, run.getStatus());
        assertEquals("대상 없음", run.getMessage());
        assertEquals(1, runRepository.count());
    }

    @DisplayName("자동 실행은 대상이 없으면 이력을 남기지 않는다")
    @Test
    void auto_run_skips_empty_history() {
        // 5분 주기 스케줄 하나가 90일이면 "대상 없음" 행만 25,920개가 된다
        Schedule schedule = save(scheduleOf("auto", true, true));

        ScheduleRun run = executionService.execute(schedule, ScheduleRun.TriggerType.AUTO, "SYSTEM");

        assertNull(run, "실행 이력을 만들지 않는다");
        assertEquals(0, runRepository.count());

        Schedule reloaded = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertEquals(ScheduleRun.RunStatus.COMPLETED, reloaded.getLastRunStatus());
        assertEquals("대상 없음", reloaded.getLastRunMessage());
        assertNotNull(reloaded.getLastRunAt(), "언제 돌았는지는 남아야 한다");
        assertNotNull(reloaded.getNextRunAt(), "다음 실행 시각이 다시 계산된다");
    }

    // ── 재시도 상한 ────────────────────────────────────────────

    @DisplayName("재시도 상한을 넘긴 워크로드는 대상에서 빠진다")
    @Test
    void over_retry_limit_dropped() {
        Workload exhausted = new Workload("포기한작업", 1, 1024);
        exhausted.setResourceType(Workload.ResourceType.VM);
        for (int i = 0; i < properties.getMaxRetryCount(); i++) {
            exhausted.markFailed("자원 부족");
        }
        workloadRepository.save(exhausted);
        assertEquals(properties.getMaxRetryCount(), exhausted.resolveRetryCount());

        Schedule schedule = save(scheduleOf("retry", true, false));
        ScheduleRun run = executionService.runNow(schedule.getId(), "tester");

        assertEquals(0, run.getTargetCount(), "대상에서 빠져야 한다");
        assertTrue(run.getMessage().contains("재시도 소진 1건"), run.getMessage());
    }

    @DisplayName("재시도 여유가 있으면 대상에 남는다 (간격을 끈 경우)")
    @Test
    void retries_left_stays_target() {
        Workload retryable = new Workload("재시도가능", 1, 1024);
        retryable.setResourceType(Workload.ResourceType.VM);
        retryable.markFailed("일시적 자원 부족");
        workloadRepository.save(retryable);

        Schedule schedule = save(scheduleOf("retry-ok", true, false));
        int backoff = properties.getRetryBackoffMinutes();
        properties.setRetryBackoffMinutes(0);   // 이 시험이 보는 것은 횟수 조건이지 간격이 아니다
        ScheduleRun run;
        try {
            run = executionService.runNow(schedule.getId(), "tester");
        } finally {
            properties.setRetryBackoffMinutes(backoff);
        }

        assertEquals(1, run.getTargetCount());
        // 테스트 환경에는 메트릭 저장소가 없어 후보 노드가 0건이다
        assertEquals(ScheduleRun.RunStatus.FAILED, run.getStatus());
        assertEquals("활성 노드가 없습니다", run.getMessage());
    }

    @DisplayName("리소스 종류가 없는 기존 행은 VM 대상 실행에 포함된다")
    @Test
    void null_resource_type_included_in_vm_run() {
        workloadRepository.save(new Workload("레거시", 1, 1024));   // resourceType = null

        Schedule schedule = save(scheduleOf("vm", true, false));
        assertEquals(1, executionService.runNow(schedule.getId(), "tester").getTargetCount());
    }

    @DisplayName("다른 리소스 종류는 대상이 아니다")
    @Test
    void other_resource_type_not_target() {
        Workload gpu = new Workload("지피유", 1, 1024);
        gpu.setResourceType(Workload.ResourceType.GPU);
        workloadRepository.save(gpu);

        Schedule vmSchedule = save(scheduleOf("vm-only", true, false));
        assertEquals(0, executionService.runNow(vmSchedule.getId(), "tester").getTargetCount());
    }

    // ── 실행 후 상태 ───────────────────────────────────────────

    @DisplayName("실행이 끝나면 실행 중 표시가 풀린다")
    @Test
    void running_flag_cleared_on_end() {
        Schedule schedule = save(scheduleOf("release", true, false));

        executionService.runNow(schedule.getId(), "tester");
        Schedule reloaded = scheduleRepository.findById(schedule.getId()).orElseThrow();

        assertNotEquals(ScheduleRun.RunStatus.RUNNING, reloaded.getLastRunStatus());
        // 두 번째 실행이 막히지 않아야 한다
        assertDoesNotThrow(() -> executionService.runNow(schedule.getId(), "tester"));
    }

    @DisplayName("실행 이력에 엔진과 정책이 기록된다")
    @Test
    void history_records_engine_and_policy() {
        Schedule schedule = save(scheduleOf("ctx", true, false));

        ScheduleRun run = executionService.runNow(schedule.getId(), "tester");

        assertEquals("weighted", run.getEngineId(), "지정하지 않으면 기본 엔진");
        assertNotNull(run.getPolicyName());
        assertEquals("tester", run.getTriggeredBy());
    }

    // ── 현행 동작 고정 (이 커밋 시점의 결함을 포함한다) ──────────────

    /**
     * <b>전부 소진되면 실행을 실패로 마감한다.</b> 사람이 되살리기 전에는 아무 일도 못 하기 때문이다.
     *
     * <p>대상이 0건이면 "할 일이 없어서" 든 "전부 소진돼서" 든 똑같이 완료로 끝난다. 화면에서는
     * 두 상황을 구분할 수 없고, 스케줄은 영원히 성공한 것처럼 보인다. 뒤 커밋이 이 시험을
     * 뒤집는 것이 곧 고쳤다는 증거다.</p>
     */
    @DisplayName("전부 소진되면 실행이 실패로 끝나고 사유에 재개가 필요하다고 적힌다")
    @Test
    void an_exhausted_run_finishes_as_failed() {
        Workload exhausted = new Workload("소진", 1, 1024);
        exhausted.setResourceType(Workload.ResourceType.VM);
        for (int i = 0; i < properties.getMaxRetryCount(); i++) exhausted.markFailed("자원 부족");
        workloadRepository.save(exhausted);

        ScheduleRun run = executionService.runNow(save(scheduleOf("exhausted-green", true, false)).getId(), "tester");

        assertEquals(0, run.getTargetCount());
        assertEquals(ScheduleRun.RunStatus.FAILED, run.getStatus(),
                "전부 소진됐는데 초록불로 끝나면 화면에서 문제가 보이지 않는다");
        assertTrue(run.getMessage().contains("일괄 재개"), run.getMessage());
    }

    /**
     * <b>재시도에 간격을 둔다.</b> 방금 실패한 워크로드는 백오프가 지나기 전에는 대상이 아니다.
     *
     * <p>5분 주기 스케줄이면 25분에 다섯 번을 전부 태운다. 온도 초과 노드의 냉각 대기가
     * 10분이고 메트릭 지연 허용도 10분이라, 회복될 수 있는 원인조차 회복 전에 소진된다.</p>
     */
    @DisplayName("방금 실패한 워크로드는 백오프 안에는 대상에서 빠진다")
    @Test
    void a_just_failed_workload_waits_for_the_backoff() {
        Workload justFailed = new Workload("방금실패", 1, 1024);
        justFailed.setResourceType(Workload.ResourceType.VM);
        justFailed.markFailed("일시적 자원 부족");
        workloadRepository.save(justFailed);

        ScheduleRun run = executionService.runNow(save(scheduleOf("no-backoff", true, false)).getId(), "tester");

        assertEquals(0, run.getTargetCount(),
                "실패 직후인데 다시 시도한다 — 냉각 대기·메트릭 지연이 회복되기 전에 기회를 태운다");
        assertTrue(run.getMessage().contains("대상 없음"), run.getMessage());
    }

    /**
     * <b>소진된 워크로드를 되살리는 길은 한 건씩뿐이다.</b> 단건 상태 변경은 이미 되고,
     * 되살리면 재시도 횟수가 0 으로 지워진다. 없는 것은 여러 건을 한 번에 되살리는 길이다.
     */
    @DisplayName("현행: 소진 재개는 단건으로만 되고 재시도 횟수가 지워진다")
    @Test
    void currently_only_one_by_one_resume_is_possible() {
        Workload exhausted = new Workload("소진2", 1, 1024);
        exhausted.setResourceType(Workload.ResourceType.VM);
        for (int i = 0; i < properties.getMaxRetryCount(); i++) exhausted.markFailed("자원 부족");
        Workload saved = workloadRepository.save(exhausted);

        assertEquals(properties.getMaxRetryCount(), saved.resolveRetryCount());
        assertTrue(saved.transitionTo(Workload.WorkloadStatus.PENDING), "실패 → 대기는 허용된다");
        assertEquals(0, saved.resolveRetryCount(), "되살리면 재시도 횟수가 지워진다");
    }

    /**
     * 백오프로 밀린 것은 <b>실패가 아니다</b> — 시간이 지나면 저절로 풀리므로 정상으로 적고
     * 얼마 뒤에 다시 시도하는지 사유에 남긴다.
     */
    @DisplayName("재시도 대기만 남으면 실행은 정상이고 사유에 대기 건수와 간격이 적힌다")
    @Test
    void a_backoff_only_run_stays_completed() {
        Workload waiting = new Workload("대기", 1, 1024);
        waiting.setResourceType(Workload.ResourceType.VM);
        waiting.markFailed("일시적 자원 부족");
        workloadRepository.save(waiting);

        ScheduleRun run = executionService.runNow(save(scheduleOf("backoff-wait", true, false)).getId(), "tester");

        assertEquals(0, run.getTargetCount());
        assertEquals(ScheduleRun.RunStatus.COMPLETED, run.getStatus(),
                "시간이 지나면 풀릴 일을 실패로 적으면 진짜 실패가 묻힌다");
        assertTrue(run.getMessage().contains("재시도 대기 1건"), run.getMessage());
        assertTrue(run.getMessage().contains(properties.getRetryBackoffMinutes() + "분"), run.getMessage());
    }

    // ── 일괄 재개 ──────────────────────────────────────

    /**
     * 소진된 워크로드를 종류로 골라 한 번에 되살린다. 되살리면 재시도 횟수와 마지막 실패
     * 시각이 지워져 백오프에도 걸리지 않고 곧바로 대상이 된다.
     */
    @DisplayName("일괄 재개는 소진된 것만 되살리고 재시도 횟수·실패 시각을 지운다")
    @Test
    void bulk_resume_revives_only_exhausted_workloads() {
        Workload exhausted = new Workload("소진-일괄", 1, 1024);
        exhausted.setResourceType(Workload.ResourceType.VM);
        for (int i = 0; i < properties.getMaxRetryCount(); i++) exhausted.markFailed("자원 부족");
        workloadRepository.save(exhausted);

        Workload stillTrying = new Workload("여유있음", 1, 1024);
        stillTrying.setResourceType(Workload.ResourceType.VM);
        stillTrying.markFailed("일시적");
        workloadRepository.save(stillTrying);

        List<Workload> resumed = schedulingService.resumeExhausted(Workload.ResourceType.VM, null, null);

        assertEquals(1, resumed.size(), "소진되지 않은 워크로드까지 되살렸다");
        assertEquals("소진-일괄", resumed.get(0).getName());
        assertEquals(0, resumed.get(0).resolveRetryCount());
        assertNull(resumed.get(0).getLastFailedAt(), "실패 시각이 남으면 백오프가 다시 붙잡는다");
        assertEquals(Workload.WorkloadStatus.PENDING, resumed.get(0).getStatus());
    }

    @DisplayName("조건 없는 전체 재개는 받지 않는다")
    @Test
    void bulk_resume_requires_a_filter() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> schedulingService.resumeExhausted(null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("하나는 지정"), e.getMessage());
    }

    @DisplayName("기간의 시작이 끝보다 뒤면 400")
    @Test
    void bulk_resume_rejects_an_inverted_range() {
        OffsetDateTime now = OffsetDateTime.now();
        BusinessException e = assertThrows(BusinessException.class,
                () -> schedulingService.resumeExhausted(null, now, now.minusDays(1)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @DisplayName("기간 밖에서 실패한 워크로드는 되살리지 않는다")
    @Test
    void bulk_resume_respects_the_period() {
        Workload exhausted = new Workload("소진-기간", 1, 1024);
        exhausted.setResourceType(Workload.ResourceType.VM);
        for (int i = 0; i < properties.getMaxRetryCount(); i++) exhausted.markFailed("자원 부족");
        workloadRepository.save(exhausted);

        OffsetDateTime longAgo = OffsetDateTime.now().minusDays(30);
        assertTrue(schedulingService.resumeExhausted(null, longAgo.minusDays(1), longAgo).isEmpty(),
                "기간 밖인데 되살렸다");
        assertEquals(1, schedulingService.resumeExhausted(null, OffsetDateTime.now().minusHours(1), null).size());
    }

    private Schedule scheduleOf(String key, boolean enabled, boolean autoRun) {
        Schedule schedule = new Schedule(key, "테스트 " + key, "0 0 2 * * ?");
        schedule.setResourceType(Workload.ResourceType.VM);
        schedule.setEnabled(enabled);
        schedule.setAutoRun(autoRun);
        return schedule;
    }

    private Schedule save(Schedule schedule) {
        return scheduleRepository.save(schedule);
    }
}

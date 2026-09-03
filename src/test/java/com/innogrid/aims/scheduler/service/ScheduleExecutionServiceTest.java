package com.innogrid.aims.scheduler.service;

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

    @DisplayName("재시도 여유가 있으면 대상에 남는다")
    @Test
    void retries_left_stays_target() {
        Workload retryable = new Workload("재시도가능", 1, 1024);
        retryable.setResourceType(Workload.ResourceType.VM);
        retryable.markFailed("일시적 자원 부족");
        workloadRepository.save(retryable);

        Schedule schedule = save(scheduleOf("retry-ok", true, false));
        ScheduleRun run = executionService.runNow(schedule.getId(), "tester");

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

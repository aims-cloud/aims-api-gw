package com.innogrid.aims.scheduler.service;

import org.junit.jupiter.api.BeforeEach;
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

    @Test
    void 사용하지_않는_스케줄은_실행할_수_없다() {
        Schedule schedule = save(scheduleOf("off", false, false));

        BusinessException e = assertThrows(BusinessException.class,
                () -> executionService.runNow(schedule.getId(), "tester"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    @Test
    void 워크로드가_없어도_실행_자체는_막지_않는다() {
        Schedule schedule = save(scheduleOf("empty", true, false));

        assertDoesNotThrow(() -> executionService.runNow(schedule.getId(), "tester"));
    }

    @Test
    void 이미_실행_중이면_409_이다() {
        Schedule schedule = scheduleOf("busy", true, false);
        schedule.recordRun(ScheduleRun.RunStatus.RUNNING, null, null, null);
        Schedule saved = save(schedule);

        BusinessException e = assertThrows(BusinessException.class,
                () -> executionService.runNow(saved.getId(), "tester"));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    // ── 대상 0건 처리 ──────────────────────────────────────────

    @Test
    void 수동_실행은_대상이_없어도_이력을_남긴다() {
        Schedule schedule = save(scheduleOf("manual", true, false));

        ScheduleRun run = executionService.runNow(schedule.getId(), "tester");

        assertNotNull(run);
        assertEquals(ScheduleRun.TriggerType.MANUAL, run.getTriggerType());
        assertEquals(ScheduleRun.RunStatus.COMPLETED, run.getStatus());
        assertEquals("대상 없음", run.getMessage());
        assertEquals(1, runRepository.count());
    }

    @Test
    void 자동_실행은_대상이_없으면_이력을_남기지_않는다() {
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

    @Test
    void 재시도_상한을_넘긴_워크로드는_대상에서_빠진다() {
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

    @Test
    void 재시도_여유가_있으면_대상에_남는다() {
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

    @Test
    void 리소스_종류가_없는_기존_행은_VM_대상_실행에_포함된다() {
        workloadRepository.save(new Workload("레거시", 1, 1024));   // resourceType = null

        Schedule schedule = save(scheduleOf("vm", true, false));
        assertEquals(1, executionService.runNow(schedule.getId(), "tester").getTargetCount());
    }

    @Test
    void 다른_리소스_종류는_대상이_아니다() {
        Workload gpu = new Workload("지피유", 1, 1024);
        gpu.setResourceType(Workload.ResourceType.GPU);
        workloadRepository.save(gpu);

        Schedule vmSchedule = save(scheduleOf("vm-only", true, false));
        assertEquals(0, executionService.runNow(vmSchedule.getId(), "tester").getTargetCount());
    }

    // ── 실행 후 상태 ───────────────────────────────────────────

    @Test
    void 실행이_끝나면_실행_중_표시가_풀린다() {
        Schedule schedule = save(scheduleOf("release", true, false));

        executionService.runNow(schedule.getId(), "tester");
        Schedule reloaded = scheduleRepository.findById(schedule.getId()).orElseThrow();

        assertNotEquals(ScheduleRun.RunStatus.RUNNING, reloaded.getLastRunStatus());
        // 두 번째 실행이 막히지 않아야 한다
        assertDoesNotThrow(() -> executionService.runNow(schedule.getId(), "tester"));
    }

    @Test
    void 실행_이력에_엔진과_정책이_기록된다() {
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

package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.innogrid.aims.scheduler.dto.ScheduleUpdateRequest;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 차례를 기다린 자동 실행이 시작 직전 값으로 도는가.
 *
 * <p>폴러는 예정 시각이 지난 스케줄 엔티티를 그대로 단일 워커 큐에 넣는다. 앞 실행이 길면 뒤 스케줄은
 * 그만큼 기다리는데, 그 사이 편집·삭제가 끼면 넘겨받은 사본은 낡는다. 여기서는 폴링 때의 사본을 먼저
 * 떼어 두고(DB 에서 읽은 뒤 트랜잭션 밖이라 분리된 사본) → 편집·삭제를 자기 트랜잭션으로 커밋한 뒤 →
 * 그 사본으로 러너의 실행 메서드를 직접 불러, 큐에서 기다리는 동안 생긴 변경을 스레드 없이 재현한다.</p>
 *
 * <p><b>이 클래스는 {@code @Transactional} 을 쓰지 않는다.</b> 트랜잭션으로 감싸면 편집이 같은 관리
 * 엔티티를 바꿔 사본이 낡지 않는다. 커밋된 값을 다시 읽어 확인한다. 실행 이력을 먼저 지운다 —
 * {@link ScheduleRunnerTest} 가 자동 실행 이력이 하나도 없음을 전역으로 확인한다.</p>
 */
@SpringBootTest
class ScheduleQueuedRunTest {

    private static final String PREFIX = "queued-";

    @Autowired ScheduleRunner runner;
    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingPolicyRepository policyRepository;
    @Autowired ObjectMapper objectMapper;

    @AfterEach
    void clean() {
        scheduleRepository.findAll().stream()
                .filter(s -> s.getScheduleId().startsWith(PREFIX))
                .forEach(s -> runRepository.deleteAll(runRepository.findByScheduleId(s.getId())));
        historyRepository.findAll().stream()
                .filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith(PREFIX))
                .forEach(historyRepository::delete);
        workloadRepository.findAll().stream()
                .filter(w -> w.getName().startsWith(PREFIX))
                .forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream()
                .filter(s -> s.getScheduleId().startsWith(PREFIX))
                .forEach(scheduleRepository::delete);
        policyRepository.findAll().stream()
                .filter(p -> p.getName().startsWith(PREFIX))
                .forEach(policyRepository::delete);
    }

    /** 사용·자동 실행이 켜지고 예정 시각이 이미 지난 스케줄 — 폴러가 집어 갈 상태. */
    private Schedule due(String id) {
        Schedule schedule = new Schedule(id, id, "0 0 3 * * ?");
        schedule.setEnabled(true);
        schedule.setAutoRun(true);
        schedule.setNextRunAt(OffsetDateTime.now().minusMinutes(1));
        return scheduleRepository.saveAndFlush(schedule);
    }

    /** 폴러가 넘긴 사본 — DB 에서 읽은 뒤 트랜잭션 밖이라 분리된 엔티티. */
    private Schedule polled(Long id) {
        return scheduleRepository.findById(id).orElseThrow();
    }

    private ScheduleUpdateRequest body(String json) {
        return objectMapper.readValue(json, ScheduleUpdateRequest.class);
    }

    private void assertNothingRan(Long id) {
        Schedule after = scheduleRepository.findById(id).orElseThrow();
        assertNull(after.getLastRunStatus(), "실행 권한을 잡은 적도 끝낸 적도 없다");
        assertNull(after.getLastRunAt());
        assertTrue(runRepository.findByScheduleId(id).isEmpty(), "실행 이력이 생기지 않는다");
    }

    @DisplayName("차례를 기다리는 사이 사용 여부를 끄면 자동 실행은 돌지 않고 아무것도 남기지 않는다")
    @Test
    void disabled_while_waiting_does_not_run() {
        Schedule saved = due(PREFIX + "disabled");
        Schedule stale = polled(saved.getId());

        scheduleService.update(saved.getId(), body("{\"enabled\":false}"));
        runner.executeQuietly(stale);

        assertNothingRan(saved.getId());
        assertNull(scheduleRepository.findById(saved.getId()).orElseThrow().getNextRunAt(), "편집이 둔 다음 실행 시각(없음) 그대로");
    }

    @DisplayName("차례를 기다리는 사이 자동 실행을 끄면 기다리던 회차는 돌지 않는다")
    @Test
    void auto_run_turned_off_while_waiting_does_not_run() {
        Schedule saved = due(PREFIX + "autorun-off");
        Schedule stale = polled(saved.getId());

        scheduleService.update(saved.getId(), body("{\"autoRun\":false}"));
        runner.executeQuietly(stale);

        assertNothingRan(saved.getId());
        assertTrue(scheduleRepository.findById(saved.getId()).orElseThrow().isEnabled(), "사용 여부는 그대로");
    }

    @DisplayName("차례를 기다리는 사이 바꾼 리소스 종류·엔진·정책이 그 회차에 적용된다")
    @Test
    void settings_changed_while_waiting_are_applied() {
        Workload gpu = new Workload(PREFIX + "w-gpu", 1, 64);
        gpu.setResourceType(Workload.ResourceType.GPU);
        workloadRepository.saveAndFlush(gpu);
        SchedulingPolicy policy = new SchedulingPolicy(PREFIX + "policy", "기다리는 사이 지정");
        policy.setResourceType(Workload.ResourceType.GPU);
        policy.setActive(false);
        policy = policyRepository.saveAndFlush(policy);

        Schedule saved = due(PREFIX + "settings");          // 종류 없음(VM) · 엔진 없음 · 정책 없음
        Schedule stale = polled(saved.getId());

        scheduleService.update(saved.getId(), body("{\"resourceType\":\"GPU\",\"engine\":\"power\",\"policyId\":" + policy.getId() + "}"));
        runner.executeQuietly(stale);

        List<ScheduleRun> runs = runRepository.findByScheduleId(saved.getId());
        assertEquals(1, runs.size(), "바뀐 종류의 대상이 있어 실행 이력이 남는다");
        ScheduleRun run = runs.get(0);
        assertEquals(ScheduleRun.TriggerType.AUTO, run.getTriggerType());
        assertEquals("power", run.getEngineId(), "기다리는 사이 바꾼 엔진으로 돈다");
        assertEquals(policy.getName(), run.getPolicyName(), "기다리는 사이 지정한 정책으로 돈다");
        assertTrue(run.getTargetCount() >= 1, "바뀐 종류의 워크로드가 대상이 된다");
    }

    @DisplayName("차례를 기다리는 사이 지운 스케줄은 예외 없이 건너뛴다")
    @Test
    void deleted_while_waiting_is_skipped_quietly() {
        Schedule saved = due(PREFIX + "deleted");
        Schedule stale = polled(saved.getId());

        scheduleService.delete(List.of(saved.getId()));
        assertDoesNotThrow(() -> runner.executeQuietly(stale));

        assertTrue(scheduleRepository.findById(saved.getId()).isEmpty(), "지워진 채 그대로");
        assertTrue(runRepository.findByScheduleId(saved.getId()).isEmpty(), "실행 이력이 생기지 않는다");
    }
}

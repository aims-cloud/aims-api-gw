package com.innogrid.aims.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.innogrid.aims.monitoring.dto.log.PortalLogResult;
import com.innogrid.aims.monitoring.dto.log.PortalLogType;
import com.innogrid.aims.monitoring.entity.PortalLogEntry;
import com.innogrid.aims.monitoring.log.PortalLogService;
import com.innogrid.aims.monitoring.repository.PortalLogRepository;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.PlacementInputRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunInputRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

/**
 * 자동 실행 실패 · 복구의 포털 로그 — 러너가 자동 실행 1회 결과를 SYSTEM 행으로 남기는지(화면 「스케줄 자동 실행」),
 * 상태가 바뀔 때만 남기는지, 대상 0건으로 끝난 회차를 복구로 보지 않는지, 수동 실행이 섞이지 않는지, 기동 회수 요약이 남는지 본다.
 *
 * <p>러너 폴링은 테스트에서 돌지 않으므로 {@code executeQuietly} · {@code recoverStuckRunsQuietly} 를 직접 부른다.
 * 수집기는 빈 스냅샷으로 두고, 배치가 성공해야 하는 회차에서만 노드 1대를 준다. 컨텍스트는 스케줄 화면 포털 로그 테스트와
 * 같은 조합이다. 자동 실행이 스케줄을 읽은 뒤 즉시 실행이 실행 권한을 먼저 잡는 순간은, 읽은 사본을 돌려주는 저장소 mock 으로
 * 만든 러너로 본다(가짜 빈이 아니라 컨텍스트는 그대로). 행은 이 클래스가 만든 스케줄의 업무 키 · PK 로 고르고, 끝나면 만든
 * 스케줄 · 워크로드 · 실행 이력 · 전달 기록 · 행을 지운다(자동 실행 이력이 남으면 러너 테스트의 「테스트 중 자동 실행 없음」 확인이 깨진다).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ScheduleAutoRunPortalLogTest {

    private static final String PREFIX = "plogar-";
    private static final Workload.ResourceType TYPE = Workload.ResourceType.NPU;

    @Autowired ScheduleRunner runner;
    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired ScheduleRunInputRepository inputRepository;
    @Autowired PlacementInputRepository placementInputRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired SchedulingPolicyRepository policyRepository;
    @Autowired PortalLogRepository portalLogRepository;
    @Autowired PortalLogService portalLogService;
    @Autowired SchedulerProperties properties;
    @MockitoBean NodeResourceCollector collector;

    private long baseline;

    @BeforeEach
    void setUp() {
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(List.of(), List.of()));
        baseline = portalLogRepository.findAll().stream().mapToLong(PortalLogEntry::getId).max().orElse(0);
    }

    @AfterEach
    void clean() {
        historyRepository.findAll().stream()
                .filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith(PREFIX))
                .forEach(historyRepository::delete);
        dispatchRepository.findAll().stream()
                .filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith(PREFIX))
                .forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith(PREFIX)).forEach(s -> {
            if (s.getLastRunStatus() == ScheduleRun.RunStatus.RUNNING) {
                s.recordRun(ScheduleRun.RunStatus.FAILED, s.getLastRunAt(), OffsetDateTime.now(), "테스트 뒤 마감");
                scheduleRepository.saveAndFlush(s);
            }
            scheduleService.delete(List.of(s.getId()));
        });
        policyRepository.findAll().stream().filter(p -> p.getName().startsWith(PREFIX)).forEach(policyRepository::delete);
        portalLogRepository.deleteAll(portalLogRepository.findAll().stream()
                .filter(l -> l.getId() > baseline && ScheduleRunner.PORTAL_LOG_SCREEN.equals(l.getScreen())).toList());
    }

    @DisplayName("소진으로 실패한 자동 실행은 실행 이력 없이 SYSTEM 실패 행 하나를 남기고 설명은 스케줄 상세의 메시지와 같다")
    @Test
    void exhausted_auto_run_leaves_one_system_failure_row() {
        exhausted("t1w");
        Schedule schedule = autoSchedule("t1");

        autoRun(schedule);

        Schedule after = reload(schedule);
        assertEquals(ScheduleRun.RunStatus.FAILED, after.getLastRunStatus());
        assertTrue(runRepository.findByScheduleId(schedule.getId()).isEmpty(), "대상 0건 자동 실행은 실행 이력을 만들지 않는다");
        List<PortalLogEntry> rows = rows(schedule);
        assertEquals(1, rows.size());
        PortalLogEntry row = rows.get(0);
        assertEquals(PortalLogType.SYSTEM, row.getType());
        assertEquals("스케줄 자동 실행", row.getScreen(), "팀의 SYSTEM 화면 이름 관례대로 하위 시스템 이름");
        assertEquals("장애", row.getAction());
        assertEquals(PortalLogResult.FAILURE, row.getResult());
        assertEquals(what(schedule) + " 실패", row.getContent());
        assertEquals(after.getLastRunMessage(), row.getDescription(), "설명은 스케줄 상세의 최근 실행 메시지와 같다");
        assertNull(row.getUserId());
        assertNull(row.getRequestId());
        assertNull(row.getRefType());
        assertNull(row.getRefId());
    }

    @DisplayName("행은 상태가 바뀔 때만 남는다 — 실패가 이어지거나 대상 0건으로 끝나면 그대로이고, 실행 이력이 있는 완료에서 복구 행 하나")
    @Test
    void auto_run_rows_are_left_only_when_state_changes() {
        Workload stuck = exhausted("t2w");
        Schedule schedule = autoSchedule("t2");

        autoRun(schedule);
        assertEquals(1, rows(schedule).size(), "첫 실패");
        autoRun(schedule);
        assertEquals(1, rows(schedule).size(), "실패가 이어지면 그대로");

        workloadRepository.delete(stuck);
        autoRun(schedule);
        assertEquals(ScheduleRun.RunStatus.COMPLETED, reload(schedule).getLastRunStatus(), "대상 없음");
        assertEquals(1, rows(schedule).size(), "대상 0건 완료는 복구로 보지 않는다");

        giveNode();
        placeable("t2p");
        autoRun(schedule);
        assertEquals(ScheduleRun.RunStatus.COMPLETED, reload(schedule).getLastRunStatus(), "배치돼 실행 이력이 있는 완료");
        List<PortalLogEntry> rows = rows(schedule);
        assertEquals(2, rows.size());
        PortalLogEntry recovered = rows.get(1);
        assertEquals("복구", recovered.getAction());
        assertEquals(PortalLogResult.SUCCESS, recovered.getResult());
        assertEquals(what(schedule) + " 복구", recovered.getContent());
        assertNull(recovered.getDescription());

        placeable("t2q");
        autoRun(schedule);
        assertEquals(2, runRepository.findByScheduleId(schedule.getId()).size(), "실행 이력이 있는 완료가 이어짐");
        assertEquals(2, rows(schedule).size(), "실행 이력이 있는 완료가 이어지면 그대로");
    }

    @DisplayName("예외로 끝난 자동 실행도 실패 행 하나를 남기고 설명은 실패를 마감한 메시지와 같다")
    @Test
    void auto_run_failed_by_exception_leaves_row_with_schedule_message() {
        SchedulingPolicy policy = new SchedulingPolicy(PREFIX + "t3-policy", "지울 정책");
        policy.setResourceType(TYPE);
        policy.setActive(false);
        policy = policyRepository.saveAndFlush(policy);
        Schedule schedule = autoSchedule("t3");
        schedule.setPolicyId(policy.getId());
        schedule = scheduleRepository.saveAndFlush(schedule);
        policyRepository.delete(policy);

        autoRun(schedule);

        Schedule after = reload(schedule);
        assertEquals(ScheduleRun.RunStatus.FAILED, after.getLastRunStatus(), "지정 정책이 없어 실패로 마감된다");
        List<PortalLogEntry> rows = rows(schedule);
        assertEquals(1, rows.size());
        assertEquals(PortalLogResult.FAILURE, rows.get(0).getResult());
        assertEquals(after.getLastRunMessage(), rows.get(0).getDescription());
    }

    @DisplayName("실행 권한을 잡지 못한 자동 실행은 아무 행도 남기지 않는다")
    @Test
    void auto_run_without_run_lock_leaves_no_row() {
        Schedule schedule = autoSchedule("t4");
        schedule.recordRun(ScheduleRun.RunStatus.RUNNING, OffsetDateTime.now(), null, null);
        schedule = scheduleRepository.saveAndFlush(schedule);

        autoRun(schedule);

        assertEquals(ScheduleRun.RunStatus.RUNNING, reload(schedule).getLastRunStatus(), "실행 권한을 못 잡아 그대로");
        assertTrue(rows(schedule).isEmpty());
    }

    @DisplayName("자동 실행이 읽은 뒤 즉시 실행이 실행 권한을 먼저 잡으면 읽은 사본에 남은 지난 실패로 행을 남기지 않는다")
    @Test
    void auto_run_preempted_after_read_does_not_report_previous_failure() {
        Schedule schedule = autoSchedule("t8");
        Schedule stale = reload(schedule);   // 자동 실행이 읽은 사본 — 지난 회차가 실패
        stale.recordRun(ScheduleRun.RunStatus.FAILED, OffsetDateTime.now().minusMinutes(2),
                OffsetDateTime.now().minusMinutes(1), "지난 자동 실행 실패");
        schedule.recordRun(ScheduleRun.RunStatus.RUNNING, OffsetDateTime.now(), null, null);
        scheduleRepository.saveAndFlush(schedule);   // 읽은 뒤 즉시 실행이 실행 권한을 잡음

        ScheduleRepository readsStale = mock(ScheduleRepository.class);
        when(readsStale.findById(schedule.getId())).thenReturn(Optional.of(stale));
        ScheduleRunner preempted = new ScheduleRunner(readsStale, runRepository, historyRepository,
                inputRepository, placementInputRepository, executionService, properties, portalLogService);

        preempted.executeQuietly(stale);

        assertEquals(ScheduleRun.RunStatus.RUNNING, reload(schedule).getLastRunStatus(), "실행 권한을 못 잡아 그대로");
        assertTrue(rows(schedule).isEmpty(), "이번 회차 결과가 없으니 지난 실패를 다시 보지 않는다");
    }

    @DisplayName("수동 실행은 자동 실행 상태에 섞이지 않는다 — 수동 실패 · 수동 완료는 행을 남기지 않는다")
    @Test
    void manual_runs_do_not_change_auto_run_state() {
        Workload stuck = exhausted("t5w");
        Schedule schedule = autoSchedule("t5");

        executionService.runNow(schedule.getId(), "tester");
        assertEquals(ScheduleRun.RunStatus.FAILED, reload(schedule).getLastRunStatus());
        assertTrue(rows(schedule).isEmpty(), "수동 실패는 행을 남기지 않는다");

        autoRun(schedule);
        assertEquals(1, rows(schedule).size(), "자동 실패는 남는다");

        workloadRepository.delete(stuck);
        giveNode();
        placeable("t5p1");
        executionService.runNow(schedule.getId(), "tester");
        assertEquals(ScheduleRun.RunStatus.COMPLETED, reload(schedule).getLastRunStatus());
        assertEquals(1, rows(schedule).size(), "수동 완료는 복구 행을 남기지 않는다");

        placeable("t5p2");
        autoRun(schedule);
        assertEquals(2, rows(schedule).size(), "자동 완료에서 복구");
        assertEquals("복구", rows(schedule).get(1).getAction());
    }

    @DisplayName("실패 중에 지우고 같은 업무 키로 다시 만든 스케줄의 실패는 새 실패 행으로 남는다")
    @Test
    void recreated_schedule_with_same_key_leaves_new_failure_row() {
        exhausted("t6w");
        Schedule first = autoSchedule("t6");
        autoRun(first);
        assertEquals(1, rows(first).size());

        scheduleService.delete(List.of(first.getId()));
        Schedule second = autoSchedule("t6");
        autoRun(second);

        assertEquals(1, rows(second).size(), "다시 만든 스케줄은 PK 가 달라 첫 실패가 새로 남는다");
    }

    @DisplayName("기동 때 실행 중으로 남은 스케줄을 회수하면 요약 행 하나를 남기고 회수할 것이 없으면 남기지 않는다")
    @Test
    void startup_recovery_leaves_one_summary_row_only_when_recovered() {
        Schedule schedule = autoSchedule("t7");
        schedule.recordRun(ScheduleRun.RunStatus.RUNNING, OffsetDateTime.now(), null, null);
        schedule = scheduleRepository.saveAndFlush(schedule);
        int running = scheduleRepository.findByLastRunStatus(ScheduleRun.RunStatus.RUNNING).size();

        runner.recoverStuckRunsQuietly();

        List<PortalLogEntry> summaries = summaries();
        assertEquals(1, summaries.size());
        PortalLogEntry row = summaries.get(0);
        assertEquals(PortalLogType.SYSTEM, row.getType());
        assertEquals("스케줄 자동 실행", row.getScreen());
        assertEquals(PortalLogResult.FAILURE, row.getResult());
        assertEquals("기동 회수: 실행 중으로 남아 있던 스케줄 " + running + "건을 실패로 마감", row.getContent());
        assertEquals(ScheduleExecutionService.RECOVERED_MESSAGE, row.getDescription());
        assertEquals(ScheduleRun.RunStatus.FAILED, reload(schedule).getLastRunStatus(), "회수로 실패 마감");

        runner.recoverStuckRunsQuietly();
        assertEquals(1, summaries().size(), "회수할 것이 없으면 남기지 않는다");
    }

    private Schedule autoSchedule(String key) {
        Schedule schedule = new Schedule(PREFIX + key, PREFIX + key, "0 0 3 * * ?");
        schedule.setResourceType(TYPE);
        schedule.setEngine("weighted");
        schedule.setEnabled(true);
        schedule.setAutoRun(true);
        schedule.setNextRunAt(OffsetDateTime.now().minusMinutes(1));
        return scheduleRepository.saveAndFlush(schedule);
    }

    /** 폴링이 넘긴 사본처럼 DB 에서 읽은 분리된 엔티티로 러너의 자동 실행 1회를 부른다 */
    private void autoRun(Schedule schedule) {
        runner.executeQuietly(scheduleRepository.findById(schedule.getId()).orElseThrow());
    }

    private Schedule reload(Schedule schedule) {
        return scheduleRepository.findById(schedule.getId()).orElseThrow();
    }

    /** 재시도 상한까지 실패한 워크로드 — 자동 실행의 대상이 아니고 「재시도 소진」으로 센다 */
    private Workload exhausted(String name) {
        Workload workload = new Workload(PREFIX + name, 1, 64);
        workload.setResourceType(TYPE);
        for (int i = 0; i < properties.getMaxRetryCount(); i++) {
            workload.markFailed("테스트 준비");
        }
        return workloadRepository.saveAndFlush(workload);
    }

    private Workload placeable(String name) {
        Workload workload = new Workload(PREFIX + name, 1, 64);
        workload.setResourceType(TYPE);
        return workloadRepository.saveAndFlush(workload);
    }

    private void giveNode() {
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", PREFIX + "node:9100", 64, 262_144, 0, 10, 20, 0, 0, 500, 0, 0, 0)), List.of()));
    }

    private static String what(Schedule schedule) {
        return schedule.getScheduleId() + " (id " + schedule.getId() + ")";
    }

    private List<PortalLogEntry> rows(Schedule schedule) {
        String head = what(schedule) + " ";
        return portalLogRepository.findAll().stream()
                .filter(l -> l.getId() > baseline && ScheduleRunner.PORTAL_LOG_SCREEN.equals(l.getScreen())
                        && l.getContent() != null && l.getContent().startsWith(head))
                .sorted(Comparator.comparing(PortalLogEntry::getId))
                .toList();
    }

    private List<PortalLogEntry> summaries() {
        return portalLogRepository.findAll().stream()
                .filter(l -> l.getId() > baseline && ScheduleRunner.PORTAL_LOG_SCREEN.equals(l.getScreen()) && "회수".equals(l.getAction()))
                .toList();
    }
}

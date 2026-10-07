package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dispatch.DispatcherRegistry;
import com.innogrid.aims.scheduler.dispatch.RecordingPlacementDispatcher;
import com.innogrid.aims.scheduler.dto.ScheduleDryRunResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleRunDetailResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunPreviewResponse;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 스케줄 모의 실행 — 실행과 같은 결정을 저장 없이 낸다.
 *
 * <pre>
 * 노드      8코어 1대 (수집기 대역), CPU 10% → 가용 7.2코어, 100W / 정격 500W
 * 워크로드  GPU 종류 3건: w1 HIGH 5코어 · w2 NORMAL 5코어 · w3 LOW 1코어 (우선순위 순)
 * 기대      w1 배치 → w2 는 w1 의 예약 때문에 자원 부족 → w3 배치. 모의와 실제 실행의 결정·문구·스냅샷이 같다
 * </pre>
 */
@SpringBootTest
class ScheduleDryRunTest {

    private static final String PREFIX = "sdr-";
    private static final String NODE = "sdr-node:9100";

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired SchedulingPolicyRepository policyRepository;
    @Autowired SchedulerProperties properties;
    @MockitoBean NodeResourceCollector collector;
    @MockitoBean DispatcherRegistry registry;

    @BeforeEach
    void acceptAll() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
    }

    @AfterEach
    void clean() {
        historyRepository.findAll().stream().filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith(PREFIX)).forEach(historyRepository::delete);
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith(PREFIX)).forEach(dispatchRepository::delete);
        runRepository.findAll().stream().filter(r -> r.getScheduleKey() != null && r.getScheduleKey().startsWith(PREFIX)).forEach(runRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith(PREFIX)).forEach(s -> scheduleService.delete(List.of(s.getId())));
    }

    @DisplayName("모의 실행의 결정·문구·집계·전후 상태가 바로 이어 한 실제 실행과 같고, 앞 건의 예약이 뒤 건에 보인다")
    @Test
    void dry_run_matches_run_now_decisions() {
        eightCoreNode();
        Workload w1 = save("w1", 5, Workload.WorkloadPriority.HIGH);
        Workload w2 = save("w2", 5, Workload.WorkloadPriority.NORMAL);
        Workload w3 = save("w3", 1, Workload.WorkloadPriority.LOW);
        long scheduleId = schedule("match", Workload.ResourceType.GPU, true, null);

        ScheduleRunPreviewResponse preview = executionService.preview(scheduleId);
        ScheduleDryRunResponse dry = executionService.dryRun(scheduleId);
        ScheduleRun run = executionService.runNow(scheduleId, "tester");
        ScheduleRunDetailResponse detail = scheduleService.runDetail(scheduleId, run.getId());

        assertEquals(run.getStatus(), dry.status());
        assertEquals(run.getMessage(), dry.message());
        assertEquals(run.getTargetCount(), dry.targetCount());
        assertEquals(run.getSuccessCount(), dry.successCount());
        assertEquals(run.getFailedCount(), dry.failedCount());
        assertEquals(run.isTruncated(), dry.truncated());
        assertEquals(2, dry.successCount(), "w1·w3 만 배치된다: " + dry.decisions());
        assertNull(dry.emptyReason());
        assertEquals(preview.policySource(), dry.policySource(), "정책을 고른 단계는 실행 전 미리보기와 같다");
        assertEquals(preview.policyName(), dry.policyName());
        assertEquals(ScheduleRunPreviewResponse.EngineSource.SCHEDULE, dry.engineSource());
        assertEquals(run.getEngineId(), dry.engine());
        assertEquals(run.getPolicyName(), dry.policyName());

        assertEquals(detail.clusterSnapshot(), dry.clusterSnapshot(), "전후 클러스터 상태가 같다");
        assertEquals(ScheduleRun.SnapshotStatus.OK, dry.clusterSnapshot().status());

        List<ScheduleRunDetailResponse.Decision> real = detail.decisions();
        assertEquals(3, real.size());
        assertEquals(3, dry.decisions().size());
        for (int i = 0; i < 3; i++) {
            ScheduleRunDetailResponse.Decision r = real.get(i);
            ScheduleDryRunResponse.DryRunDecision d = dry.decisions().get(i);
            assertEquals(r.workloadId(), d.workloadId(), "순서 " + i);
            assertEquals(r.workloadName(), d.workloadName());
            assertEquals(r.success(), d.success());
            assertEquals(r.selectedMdcId(), d.selectedMdcId());
            assertEquals(r.selectedNodeId(), d.selectedNodeId());
            assertEquals(r.finalScore(), d.finalScore());
            assertEquals(r.reason(), d.reason());
            assertEquals(r.exclusionCodes(), d.exclusionCodes());
        }
        assertEquals(List.of(w1.getId(), w2.getId(), w3.getId()),
                dry.decisions().stream().map(ScheduleDryRunResponse.DryRunDecision::workloadId).toList(), "우선순위 순");
        ScheduleDryRunResponse.DryRunDecision second = dry.decisions().get(1);
        assertFalse(second.success(), "w2 는 w1 의 예약 때문에 실패한다");
        assertNotNull(second.exclusionCodes());
        assertTrue(second.exclusionCodes().contains("INSUFFICIENT_RESOURCE"), second.exclusionCodes());
        assertEquals(NODE, dry.decisions().get(0).selectedNodeId());
        assertEquals(NODE, dry.decisions().get(2).selectedNodeId());
    }

    @DisplayName("모의 실행은 정책·이력·전달·실행 행을 만들지 않고 워크로드를 바꾸지 않으며 노드는 한 번만 모은다")
    @Test
    @Transactional
    void dry_run_writes_nothing() {
        eightCoreNode();
        policyRepository.deleteAll();
        policyRepository.flush();
        Workload w = save("none", 1, Workload.WorkloadPriority.NORMAL);
        long scheduleId = schedule("writes", Workload.ResourceType.GPU, true, null);
        long history = historyRepository.count();
        long dispatches = dispatchRepository.count();
        long runs = runRepository.count();

        ScheduleDryRunResponse dry = executionService.dryRun(scheduleId);

        assertEquals(1, dry.successCount());
        assertEquals(SchedulingService.DEFAULT_POLICY_NAME, dry.policyName());
        assertEquals(0, policyRepository.count(), "default 정책을 저장하지 않는다");
        assertEquals(history, historyRepository.count());
        assertEquals(dispatches, dispatchRepository.count());
        assertEquals(runs, runRepository.count());
        Workload after = workloadRepository.findById(w.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.PENDING, after.getStatus());
        assertNull(after.getAssignedNodeId());
        assertEquals(w.getRetryCount(), after.getRetryCount());
        assertNull(scheduleRepository.findById(scheduleId).orElseThrow().getLastRunStatus());
        verify(collector, times(1)).collect();
    }

    @DisplayName("스케줄의 전력 예산을 실행처럼 적용한다 — 노드 전력이 예산을 넘으면 전부 실패")
    @Test
    void dry_run_applies_schedule_power_budget() {
        eightCoreNode();
        save("pb", 1, Workload.WorkloadPriority.NORMAL);
        long scheduleId = schedule("budget", Workload.ResourceType.GPU, true, 50.0);

        ScheduleDryRunResponse dry = executionService.dryRun(scheduleId);

        assertEquals(ScheduleRun.RunStatus.FAILED, dry.status());
        assertEquals(0, dry.successCount());
        assertEquals(1, dry.failedCount());
        assertTrue(dry.message().startsWith("대상 1건 전부 배치 실패 — "), dry.message());
        assertTrue(dry.decisions().get(0).exclusionCodes().contains("RUN_POWER_BUDGET_EXCEEDED"), dry.decisions().get(0).exclusionCodes());
    }

    @DisplayName("대상이 실행 1회 상한을 넘으면 실행처럼 앞에서 자르고 상한 뒤 건수와 꼬리 문구를 돌려준다")
    @Test
    void dry_run_truncates_like_run() {
        eightCoreNode();
        save("t1", 1, Workload.WorkloadPriority.HIGH);
        save("t2", 1, Workload.WorkloadPriority.NORMAL);
        save("t3", 1, Workload.WorkloadPriority.LOW);
        long scheduleId = schedule("trunc", Workload.ResourceType.GPU, true, null);
        int limit = properties.getMaxWorkloadsPerRun();
        properties.setMaxWorkloadsPerRun(2);
        try {
            ScheduleDryRunResponse dry = executionService.dryRun(scheduleId);

            assertEquals(2, dry.targetCount());
            assertTrue(dry.truncated());
            assertEquals(2, dry.decisions().size());
            assertEquals(2, dry.successCount());
            assertTrue(dry.message().endsWith("(상한 초과분은 다음 실행으로)"), dry.message());
        } finally {
            properties.setMaxWorkloadsPerRun(limit);
        }
    }

    @DisplayName("대상이 없으면 노드를 모으지 않고 실행과 같은 '대상 없음' 결과를 돌려준다")
    @Test
    void no_target_skips_node_collection() {
        long scheduleId = schedule("empty", Workload.ResourceType.CONTAINER, true, null);

        ScheduleDryRunResponse dry = executionService.dryRun(scheduleId);

        assertEquals(ScheduleRun.RunStatus.COMPLETED, dry.status());
        assertEquals("대상 없음", dry.message());
        assertEquals(ScheduleRunPreviewResponse.EmptyReason.NONE, dry.emptyReason());
        assertEquals(0, dry.targetCount());
        assertEquals(0, dry.successCount());
        assertEquals(0, dry.failedCount());
        assertEquals(ScheduleRun.SnapshotStatus.NO_TARGET, dry.clusterSnapshot().status());
        assertNull(dry.clusterSnapshot().nodeCount());
        assertTrue(dry.decisions().isEmpty());
        verify(collector, times(0)).collect();
    }

    @DisplayName("노드를 한 대도 모으지 못하면 실행처럼 실패로 답하고 결정은 비어 있다")
    @Test
    void no_nodes_fails_like_run() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(), List.of()));
        save("nn", 1, Workload.WorkloadPriority.NORMAL);
        long scheduleId = schedule("nonodes", Workload.ResourceType.GPU, true, null);

        ScheduleDryRunResponse dry = executionService.dryRun(scheduleId);

        assertEquals(ScheduleRun.RunStatus.FAILED, dry.status());
        assertEquals(1, dry.targetCount());
        assertEquals(0, dry.successCount());
        assertEquals(1, dry.failedCount());
        assertEquals(ScheduleRun.SnapshotStatus.NO_NODES, dry.clusterSnapshot().status());
        assertTrue(dry.decisions().isEmpty());
        assertNotNull(dry.message());
    }

    @DisplayName("사용 여부가 꺼진 스케줄도 모의 실행은 200 이고 enabled 로 구분한다")
    @Test
    void disabled_schedule_still_answers() {
        long scheduleId = schedule("off", Workload.ResourceType.CONTAINER, false, null);

        ScheduleDryRunResponse dry = executionService.dryRun(scheduleId);

        assertFalse(dry.enabled());
        assertEquals(ScheduleRun.RunStatus.COMPLETED, dry.status());
        assertEquals(ScheduleRunPreviewResponse.EmptyReason.NONE, dry.emptyReason());
    }

    @DisplayName("없는 스케줄과 지정 정책이 지워진 스케줄은 404 다")
    @Test
    void missing_schedule_and_deleted_policy_404() {
        BusinessException missing = assertThrows(BusinessException.class, () -> executionService.dryRun(999_999L));
        assertEquals(404, missing.getStatus().value());

        SchedulingPolicy policy = policyRepository.saveAndFlush(new SchedulingPolicy(PREFIX + "gone", "d"));
        long scheduleId = scheduleService.create(new ScheduleRequest(PREFIX + "gone", PREFIX + "gone", Workload.ResourceType.CONTAINER,
                "0 0 2 * * ?", false, true, PREFIX, "weighted", policy.getId(), null, null, null)).id();
        policyRepository.delete(policy);
        policyRepository.flush();

        BusinessException deleted = assertThrows(BusinessException.class, () -> executionService.dryRun(scheduleId));
        assertEquals(404, deleted.getStatus().value());
        assertTrue(deleted.getMessage().contains("배치 정책"), deleted.getMessage());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private void eightCoreNode() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", NODE, 8, 65_536, 0, 10, 20, 0, 100, 500, 0, 0, 0)), List.of()));
    }

    private long schedule(String key, Workload.ResourceType type, boolean enabled, Double powerBudget) {
        return scheduleService.create(new ScheduleRequest(PREFIX + key, PREFIX + key, type, "0 0 2 * * ?",
                false, enabled, PREFIX, "weighted", null, powerBudget, null, null)).id();
    }

    private Workload save(String name, int cpu, Workload.WorkloadPriority priority) {
        Workload w = new Workload(PREFIX + name, cpu, 64);
        w.setResourceType(Workload.ResourceType.GPU);
        w.setPriority(priority);
        return workloadRepository.saveAndFlush(w);
    }
}

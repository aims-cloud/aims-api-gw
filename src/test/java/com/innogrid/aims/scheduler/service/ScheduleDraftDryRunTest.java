package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dispatch.DispatcherRegistry;
import com.innogrid.aims.scheduler.dispatch.RecordingPlacementDispatcher;
import com.innogrid.aims.scheduler.dto.ScheduleDryRunResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleRunPreviewResponse;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
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
 * 저장 전 스케줄 모의 실행 — 생성 폼 값이 같은 값을 저장한 스케줄의 모의 실행과 같은 결과를 내고, 어떤 행도 쓰지 않으며,
 * 온도 상한 칸은 노드를 모으기 전에 400 으로 막는다.
 */
@SpringBootTest
class ScheduleDraftDryRunTest {

    private static final String PREFIX = "sddr-";
    private static final String NODE = "sddr-node:9100";

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired SchedulingPolicyRepository policyRepository;
    @Autowired ThermalModel thermalModel;
    @MockitoBean NodeResourceCollector collector;
    @MockitoBean DispatcherRegistry registry;

    @BeforeEach
    void acceptAll() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
    }

    @AfterEach
    void clean() {
        thermalModel.clearCritical(NODE);
        historyRepository.findAll().stream().filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith(PREFIX)).forEach(historyRepository::delete);
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith(PREFIX)).forEach(dispatchRepository::delete);
        runRepository.findAll().stream().filter(r -> r.getScheduleKey() != null && r.getScheduleKey().startsWith(PREFIX)).forEach(runRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith(PREFIX)).forEach(s -> scheduleService.delete(List.of(s.getId())));
    }

    @DisplayName("같은 값을 저장한 스케줄의 모의 실행과 id 를 뺀 열여섯 값이 같다 — 결정 목록과 전후 상태까지")
    @Test
    void draft_matches_saved_dry_run() {
        eightCoreNode();
        save("w1", 5, Workload.WorkloadPriority.HIGH);
        save("w2", 5, Workload.WorkloadPriority.NORMAL);
        save("w3", 1, Workload.WorkloadPriority.LOW);
        ScheduleRequest form = form(PREFIX + "cmp", Workload.ResourceType.GPU, true, 400.0);
        long savedId = scheduleService.create(form).id();

        ScheduleDryRunResponse saved = executionService.dryRun(savedId);
        ScheduleDryRunResponse draft = executionService.dryRunDraft(form);

        assertNull(draft.id());
        assertEquals(saved.scheduleId(), draft.scheduleId());
        assertEquals(saved.enabled(), draft.enabled());
        assertEquals(saved.resourceType(), draft.resourceType());
        assertEquals(saved.policyName(), draft.policyName());
        assertEquals(saved.policySource(), draft.policySource());
        assertEquals(saved.engine(), draft.engine());
        assertEquals(saved.engineSource(), draft.engineSource());
        assertEquals(saved.status(), draft.status());
        assertEquals(saved.message(), draft.message());
        assertEquals(saved.emptyReason(), draft.emptyReason());
        assertEquals(saved.targetCount(), draft.targetCount());
        assertEquals(saved.successCount(), draft.successCount());
        assertEquals(saved.failedCount(), draft.failedCount());
        assertEquals(saved.truncated(), draft.truncated());
        assertEquals(saved.clusterSnapshot(), draft.clusterSnapshot());
        assertEquals(saved.decisions(), draft.decisions());
        assertEquals(3, draft.targetCount(), "GPU 세 건이 대상이다: " + draft.decisions());
        assertEquals(2, draft.successCount(), "w2 는 w1 의 예약 때문에 실패한다");
    }

    @DisplayName("스케줄·정책·이력·전달·실행 행을 만들지 않고 워크로드를 바꾸지 않으며 노드는 한 번만 모은다 — 정책이 하나도 없어도 default 를 만들지 않는다")
    @Test
    @Transactional
    void draft_writes_nothing() {
        eightCoreNode();
        policyRepository.deleteAll();
        policyRepository.flush();
        Workload w = save("none", 1, Workload.WorkloadPriority.NORMAL);
        long schedules = scheduleRepository.count();
        long history = historyRepository.count();
        long dispatches = dispatchRepository.count();
        long runs = runRepository.count();

        ScheduleDryRunResponse draft = executionService.dryRunDraft(form(PREFIX + "none", Workload.ResourceType.GPU, true, null));

        assertEquals(1, draft.successCount());
        assertEquals(SchedulingService.DEFAULT_POLICY_NAME, draft.policyName());
        assertEquals(0, policyRepository.count(), "default 정책을 저장하지 않는다");
        assertEquals(schedules, scheduleRepository.count(), "스케줄을 만들지 않는다");
        assertEquals(history, historyRepository.count());
        assertEquals(dispatches, dispatchRepository.count());
        assertEquals(runs, runRepository.count());
        Workload after = workloadRepository.findById(w.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.PENDING, after.getStatus());
        assertNull(after.getAssignedNodeId());
        verify(collector, times(1)).collect();
    }

    @DisplayName("온도 상한 칸이 있으면 노드를 모으기 전에 400 이고 냉각 대기 표시도 남지 않는다")
    @Test
    void temperature_override_rejected_before_collecting() {
        eightCoreNode();
        save("temp", 1, Workload.WorkloadPriority.NORMAL);
        // 노드 온도 0℃ > 상한 −1℃ — 검사를 지우면 평가가 실제로 냉각 대기 표시를 남긴다
        ScheduleRequest form = new ScheduleRequest(PREFIX + "temp", PREFIX + "temp", Workload.ResourceType.GPU, "0 0 2 * * ?",
                false, true, PREFIX, "weighted", null, null, -1.0, null);

        BusinessException e = assertThrows(BusinessException.class, () -> executionService.dryRunDraft(form));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("온도 상한"), e.getMessage());
        verify(collector, times(0)).collect();
        assertFalse(thermalModel.isCoolingDown(NODE));
    }

    @DisplayName("폼의 전력 상한을 실행처럼 적용한다 — 노드 전력이 상한을 넘으면 전부 실패")
    @Test
    void draft_applies_form_power_budget() {
        eightCoreNode();
        save("pb", 1, Workload.WorkloadPriority.NORMAL);

        ScheduleDryRunResponse draft = executionService.dryRunDraft(form(PREFIX + "pb", Workload.ResourceType.GPU, true, 50.0));

        assertEquals(ScheduleRun.RunStatus.FAILED, draft.status());
        assertEquals(0, draft.successCount());
        assertTrue(draft.decisions().get(0).exclusionCodes().contains("RUN_POWER_BUDGET_EXCEEDED"), draft.decisions().get(0).exclusionCodes());
    }

    @DisplayName("검증은 저장 전 미리보기와 같다 — 본문 없음·엔진·배치 순서 400, 없는 정책 404, 스케줄명·실행 주기가 없어도 200")
    @Test
    void draft_validation_like_preview() {
        eightCoreNode();
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class, () -> executionService.dryRunDraft(null)).getStatus());
        BusinessException engine = assertThrows(BusinessException.class, () -> executionService.dryRunDraft(
                new ScheduleRequest(null, null, Workload.ResourceType.CONTAINER, null, null, null, null, "no-such-engine", null, null, null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, engine.getStatus());
        assertEquals("알 수 없는 스케줄링 엔진입니다: no-such-engine", engine.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class, () -> executionService.dryRunDraft(
                new ScheduleRequest(null, null, Workload.ResourceType.CONTAINER, null, null, null, null, null, null, null, null, "random"))).getStatus());
        BusinessException policy = assertThrows(BusinessException.class, () -> executionService.dryRunDraft(
                new ScheduleRequest(null, null, Workload.ResourceType.CONTAINER, null, null, null, null, null, 999_999_999L, null, null, null)));
        assertEquals(HttpStatus.NOT_FOUND, policy.getStatus());
        assertEquals("스케줄에 지정된 배치 정책을 찾을 수 없습니다: 999999999", policy.getMessage());

        BusinessException order = assertThrows(BusinessException.class, () -> executionService.dryRunDraft(
                new ScheduleRequest(null, null, Workload.ResourceType.CONTAINER, null, null, null, null, "no-such-engine", null, null, 50.0, null)));
        assertEquals("알 수 없는 스케줄링 엔진입니다: no-such-engine", order.getMessage(), "초안 검증이 온도 상한 검사보다 먼저다");

        ScheduleDryRunResponse ok = executionService.dryRunDraft(
                new ScheduleRequest(null, null, Workload.ResourceType.CONTAINER, null, null, null, null, null, null, null, null, null));
        assertNull(ok.id());
        assertNull(ok.scheduleId());
        assertEquals(ScheduleRun.RunStatus.COMPLETED, ok.status());
        assertEquals(ScheduleRunPreviewResponse.EmptyReason.NONE, ok.emptyReason());
    }

    @DisplayName("키를 빼면 기본값으로 읽고 정책·엔진 출처는 같은 본문의 미리보기와 같다")
    @Test
    void missing_keys_read_as_defaults() {
        eightCoreNode();
        ScheduleRequest empty = new ScheduleRequest(null, null, null, null, null, null, null, null, null, null, null, null);

        ScheduleRunPreviewResponse preview = executionService.previewDraft(empty);
        ScheduleDryRunResponse draft = executionService.dryRunDraft(empty);

        assertEquals(Workload.ResourceType.VM, draft.resourceType());
        assertTrue(draft.enabled());
        assertEquals(preview.policyName(), draft.policyName());
        assertEquals(preview.policySource(), draft.policySource());
        assertEquals(preview.engine(), draft.engine());
        assertEquals(preview.engineSource(), draft.engineSource());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private void eightCoreNode() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", NODE, 8, 65_536, 0, 10, 20, 0, 100, 500, 0, 0, 0)), List.of()));
    }

    private static ScheduleRequest form(String key, Workload.ResourceType type, boolean enabled, Double powerBudget) {
        return new ScheduleRequest(key, key, type, "0 0 2 * * ?", false, enabled, PREFIX, "weighted", null, powerBudget, null, null);
    }

    private Workload save(String name, int cpu, Workload.WorkloadPriority priority) {
        Workload w = new Workload(PREFIX + name, cpu, 64);
        w.setResourceType(Workload.ResourceType.GPU);
        w.setPriority(priority);
        return workloadRepository.saveAndFlush(w);
    }
}

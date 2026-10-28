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
import com.innogrid.aims.scheduler.model.ExcludedNode;
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
 * 온도 상한을 지정하면 그 값으로 평가하되 냉각 대기를 새로 남기지 않는다.
 */
@SpringBootTest
class ScheduleDraftDryRunTest {

    private static final String PREFIX = "sddr-";
    private static final String NODE = "sddr-node:9100";
    private static final String HOT = "sddr-hot:9100";
    private static final String COOL = "sddr-cool:9100";

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
        thermalModel.clearCritical(HOT);
        thermalModel.clearCritical(COOL);
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

    @DisplayName("폼의 온도 상한으로 평가하고 냉각 대기를 남기지 않는다 — 상한을 넘은 노드만 빠진다")
    @Test
    void draft_temperature_cap_applied_without_cooldown() {
        hotAndCoolNodes();
        save("tc", 1, Workload.WorkloadPriority.NORMAL);

        ScheduleDryRunResponse draft = executionService.dryRunDraft(form(PREFIX + "tc", null, 60.0));

        assertEquals(ScheduleRun.RunStatus.COMPLETED, draft.status());
        assertEquals(1, draft.successCount());
        assertEquals(COOL, draft.decisions().get(0).selectedNodeId());
        assertTrue(draft.decisions().get(0).exclusionCodes().contains("TEMPERATURE_EXCEEDED"), draft.decisions().get(0).exclusionCodes());
        assertFalse(thermalModel.isCoolingDown(HOT), "저장하지 않은 상한으로 냉각 대기를 걸지 않는다");
        verify(collector, times(1)).collect();
    }

    @DisplayName("이미 걸린 냉각 대기는 상한별로 본다 — 65℃ 대기는 폼 60 을 막고 폼 90 은 막지 않으며 새 대기는 생기지 않는다")
    @Test
    void draft_temperature_cap_reads_existing_cooldown() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(node(HOT, 70)), List.of()));
        save("cd", 1, Workload.WorkloadPriority.NORMAL);
        thermalModel.markCritical(HOT, 65);

        ScheduleDryRunResponse low = executionService.dryRunDraft(form(PREFIX + "cd", null, 60.0));
        ScheduleDryRunResponse high = executionService.dryRunDraft(form(PREFIX + "cd", null, 90.0));

        assertEquals(0, low.successCount());
        assertTrue(low.decisions().get(0).exclusionCodes().contains("COOLING_DOWN"), low.decisions().get(0).exclusionCodes());
        assertEquals(1, high.successCount(), "상한이 더 높은 평가는 65℃ 대기에 막히지 않는다: " + high.decisions());
        assertEquals(HOT, high.decisions().get(0).selectedNodeId());
        assertTrue(thermalModel.isCoolingDown(HOT, 65));
        assertFalse(thermalModel.isCoolingDown(HOT, 66), "65℃ 보다 높은 상한의 대기가 새로 생기지 않는다");
    }

    @DisplayName("같은 값을 저장한 스케줄의 모의 실행과 같고, 다른 것은 뒤 대상에서 그 노드의 제외 사유가 냉각 대기가 아닌 온도 초과인 것뿐이다")
    @Test
    void draft_temperature_cap_matches_saved_except_cooldown_codes() {
        hotAndCoolNodes();
        save("m1", 1, Workload.WorkloadPriority.HIGH);
        save("m2", 1, Workload.WorkloadPriority.NORMAL);
        ScheduleRequest form = form(PREFIX + "mt", null, 60.0);

        // 저장 전을 먼저 돌린다 — 저장본 모의 실행은 HOT 에 냉각 대기를 걸어 뒤 호출을 바꾼다
        ScheduleDryRunResponse draft = executionService.dryRunDraft(form);
        assertFalse(thermalModel.isCoolingDown(HOT));
        ScheduleDryRunResponse saved = executionService.dryRun(scheduleService.create(form).id());

        assertNull(draft.id());
        assertEquals(saved.status(), draft.status());
        assertEquals(saved.message(), draft.message());
        assertEquals(saved.successCount(), draft.successCount());
        assertEquals(saved.failedCount(), draft.failedCount());
        assertEquals(saved.clusterSnapshot(), draft.clusterSnapshot());
        assertEquals(2, draft.successCount());
        assertEquals(saved.decisions().get(0), draft.decisions().get(0));
        ScheduleDryRunResponse.DryRunDecision d = draft.decisions().get(1), s = saved.decisions().get(1);
        assertEquals(s.workloadId(), d.workloadId());
        assertEquals(s.success(), d.success());
        assertEquals(s.selectedNodeId(), d.selectedNodeId());
        assertEquals(s.finalScore(), d.finalScore());
        assertEquals(s.reason(), d.reason());
        assertTrue(d.exclusionCodes().contains("TEMPERATURE_EXCEEDED") && !d.exclusionCodes().contains("COOLING_DOWN"), d.exclusionCodes());
        assertTrue(s.exclusionCodes().contains("COOLING_DOWN"), s.exclusionCodes());
        assertTrue(thermalModel.isCoolingDown(HOT, 60), "저장본 모의 실행은 지금처럼 냉각 대기를 남긴다");
    }

    @DisplayName("온도 상한을 지정하지 않으면 지금처럼 정책 상한으로 냉각 대기를 남긴다")
    @Test
    void draft_without_temperature_still_marks_cooldown() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(node(HOT, 80)), List.of()));
        save("nt", 1, Workload.WorkloadPriority.NORMAL);

        ScheduleDryRunResponse draft = executionService.dryRunDraft(form(PREFIX + "nt", null, null));

        assertEquals(0, draft.successCount());
        assertTrue(draft.decisions().get(0).exclusionCodes().contains("TEMPERATURE_EXCEEDED"), draft.decisions().get(0).exclusionCodes());
        assertTrue(thermalModel.isCoolingDown(HOT), "정책 상한(75℃)을 넘은 노드에 냉각 대기가 걸린다");
    }

    @DisplayName("온도 상한을 지정해도 수집에서 빠진 노드 수가 전후 상태에 그대로 실린다")
    @Test
    void draft_temperature_cap_keeps_excluded_nodes() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(node(COOL, 40)),
                List.of(new ExcludedNode("mdc-01", "sddr-gone:9100", List.of("cpu")))));
        save("ex", 1, Workload.WorkloadPriority.NORMAL);

        ScheduleDryRunResponse withCap = executionService.dryRunDraft(form(PREFIX + "ex", null, 60.0));
        ScheduleDryRunResponse withoutCap = executionService.dryRunDraft(form(PREFIX + "ex", null, null));

        assertEquals(1, withCap.clusterSnapshot().excludedCount());
        assertEquals(withoutCap.clusterSnapshot(), withCap.clusterSnapshot());
    }

    @DisplayName("온도 상한을 지정해도 폼의 전력 상한을 함께 적용한다")
    @Test
    void draft_temperature_cap_with_power_budget() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(node(HOT, 70)), List.of()));
        save("tp", 1, Workload.WorkloadPriority.NORMAL);

        ScheduleDryRunResponse draft = executionService.dryRunDraft(form(PREFIX + "tp", 50.0, 90.0));

        assertEquals(0, draft.successCount());
        assertTrue(draft.decisions().get(0).exclusionCodes().contains("RUN_POWER_BUDGET_EXCEEDED"), draft.decisions().get(0).exclusionCodes());
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
        assertEquals("알 수 없는 스케줄링 엔진입니다: no-such-engine", order.getMessage(), "초안 검증이 평가보다 먼저다");

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

    /** 70℃ 노드와 40℃ 노드 — 호출마다 새 객체라 앞 호출의 예약이 남지 않는다. */
    private void hotAndCoolNodes() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(node(HOT, 70), node(COOL, 40)), List.of()));
    }

    private static NodeResource node(String nodeId, double celsius) {
        return new NodeResource("mdc-01", nodeId, 8, 65_536, 0, 10, 20, 0, 100, 500, celsius, 0, 0);
    }

    private static ScheduleRequest form(String key, Workload.ResourceType type, boolean enabled, Double powerBudget) {
        return new ScheduleRequest(key, key, type, "0 0 2 * * ?", false, enabled, PREFIX, "weighted", null, powerBudget, null, null);
    }

    private static ScheduleRequest form(String key, Double powerBudget, Double temperature) {
        return new ScheduleRequest(key, key, Workload.ResourceType.GPU, "0 0 2 * * ?", false, true, PREFIX, "weighted", null,
                powerBudget, temperature, null);
    }

    private Workload save(String name, int cpu, Workload.WorkloadPriority priority) {
        Workload w = new Workload(PREFIX + name, cpu, 64);
        w.setResourceType(Workload.ResourceType.GPU);
        w.setPriority(priority);
        return workloadRepository.saveAndFlush(w);
    }
}

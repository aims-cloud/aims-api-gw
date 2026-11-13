package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleRunPreviewResponse;
import com.innogrid.aims.scheduler.model.EmptyReason;
import com.innogrid.aims.scheduler.model.EngineSource;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.PolicyChoice;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 저장 전 미리보기 — 폼 값으로 보는 미리보기가 같은 값을 저장한 스케줄의 미리보기와 같은 결과를 내고, 어떤 행도 쓰지 않는다.
 */
@SpringBootTest
@Transactional
class ScheduleDraftPreviewTest {

    @Autowired private ScheduleExecutionService executionService;
    @Autowired private ScheduleService scheduleService;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private ScheduleRunRepository runRepository;
    @Autowired private WorkloadRepository workloadRepository;
    @Autowired private SchedulingPolicyRepository policyRepository;
    @Autowired private SchedulerProperties properties;
    @MockitoBean private NodeResourceCollector collector;

    @BeforeEach
    void clean() {
        runRepository.deleteAll();
        scheduleRepository.deleteAll();
        workloadRepository.deleteAll();
        policyRepository.deleteAll();
        policyRepository.flush();
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(), List.of()));
    }

    private static ScheduleRequest form(Workload.ResourceType type, String engine, Long policyId,
                                        Double temperature, String order) {
        return new ScheduleRequest("draft-key", "초안", type, "0 0 2 * * ?", false, true, "설명",
                engine, policyId, null, temperature, order);
    }

    private static ScheduleRequest empty() {
        return new ScheduleRequest(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static List<Object> comparable(ScheduleRunPreviewResponse p) {
        return java.util.Arrays.asList(p.enabled(), p.resourceType(), p.policyName(), p.policySource(), p.engine(),
                p.engineSource(), p.targetCount(), p.runLimit(), p.waitingCount(), p.exhaustedCount(),
                p.emptyReason(), p.emptyMessage());
    }

    // ── 저장된 미리보기와 같은 결과 ────────────────────────────

    @DisplayName("같은 값을 저장한 스케줄의 미리보기와 id 를 뺀 열두 값이 같다")
    @Test
    void draft_matches_saved_schedule_preview() {
        SchedulingPolicy gpu = policyRepository.save(active("gpu-active", Workload.ResourceType.GPU));
        policyRepository.save(active("common", null));
        workloadRepository.save(typed("g1", Workload.ResourceType.GPU));
        workloadRepository.save(typed("g2", Workload.ResourceType.GPU));
        workloadRepository.save(failedTimes(typed("g-wait", Workload.ResourceType.GPU), 1));
        workloadRepository.save(failedTimes(typed("g-out", Workload.ResourceType.GPU), properties.getMaxRetryCount()));
        workloadRepository.save(typed("v1", Workload.ResourceType.VM));
        ScheduleRequest form = form(Workload.ResourceType.GPU, "power", gpu.getId(), 65.0, "size");

        Long savedId = scheduleService.create(form).id();
        ScheduleRunPreviewResponse fromSaved = executionService.preview(savedId);
        ScheduleRunPreviewResponse draft = executionService.previewDraft(form);

        assertNull(draft.id());
        assertEquals("draft-key", draft.scheduleId());
        assertEquals(comparable(fromSaved), comparable(draft));
        assertEquals(2, draft.targetCount());
        assertEquals(1, draft.waitingCount());
        assertEquals(1, draft.exhaustedCount());
        assertEquals("gpu-active", draft.policyName());
        assertEquals(PolicyChoice.Source.SCHEDULE, draft.policySource());
        assertEquals("power", draft.engine());
        assertEquals(EngineSource.SCHEDULE, draft.engineSource());
    }

    @DisplayName("빈 본문은 VM 과 종류별 활성 정책으로 해석되고 사용 중으로 본다")
    @Test
    void empty_form_resolves_vm_and_resource_type_policy() {
        policyRepository.save(active("vm-active", Workload.ResourceType.VM));
        workloadRepository.save(typed("v1", Workload.ResourceType.VM));
        workloadRepository.save(typed("untyped", null));

        ScheduleRunPreviewResponse p = executionService.previewDraft(empty());

        assertNull(p.id());
        assertNull(p.scheduleId());
        assertTrue(p.enabled());
        assertEquals(Workload.ResourceType.VM, p.resourceType());
        assertEquals("vm-active", p.policyName());
        assertEquals(PolicyChoice.Source.RESOURCE_TYPE, p.policySource());
        assertEquals(2, p.targetCount(), "종류를 비운 워크로드도 VM 으로 센다");
        assertNull(p.emptyReason());
    }

    @DisplayName("대상이 없으면 저장된 미리보기와 같은 사유·문구를 돌려준다")
    @Test
    void no_targets_gives_same_reason_and_message() {
        policyRepository.save(active("common", null));
        ScheduleRequest form = form(Workload.ResourceType.VM, null, null, null, null);
        Long savedId = scheduleService.create(form).id();

        ScheduleRunPreviewResponse draft = executionService.previewDraft(form);

        assertEquals(0, draft.targetCount());
        assertEquals(EmptyReason.NONE, draft.emptyReason());
        assertEquals(executionService.preview(savedId).emptyMessage(), draft.emptyMessage());
    }

    // ── 저장 없음 ───────────────────────────────────────────────

    @DisplayName("스케줄·실행·정책 행을 만들지 않고 노드도 모으지 않는다 — 정책이 하나도 없어도 default 를 만들지 않는다")
    @Test
    void writes_nothing_and_collects_nothing() {
        long schedules = scheduleRepository.count();
        long runs = runRepository.count();
        assertEquals(0, policyRepository.count());

        ScheduleRunPreviewResponse p = executionService.previewDraft(form(Workload.ResourceType.VM, null, null, null, null));

        assertEquals(PolicyChoice.Source.DEFAULT, p.policySource());
        assertEquals(schedules, scheduleRepository.count());
        assertEquals(runs, runRepository.count());
        assertEquals(0, policyRepository.count(), "읽기 요청이 default 정책을 만들면 안 된다");
        verify(collector, never()).collect();
    }

    @DisplayName("온도 상한을 주어도 저장된 정책의 온도 상한은 그대로다")
    @Test
    void temperature_override_leaves_policy_untouched() {
        SchedulingPolicy policy = active("common", null);
        policy.setMaxTemperatureCelsius(80.0);
        policy = policyRepository.save(policy);

        executionService.previewDraft(form(Workload.ResourceType.VM, null, policy.getId(), 55.0, null));

        policyRepository.flush();
        assertEquals(80.0, policyRepository.findById(policy.getId()).orElseThrow().getMaxTemperatureCelsius());
    }

    @DisplayName("사용 여부를 끄고 보내면 enabled 가 false 로 돌아오고 정책 해석은 그대로다")
    @Test
    void disabled_form_echoes_enabled_false() {
        policyRepository.save(active("common", null));
        ScheduleRequest off = new ScheduleRequest("k", "n", Workload.ResourceType.VM, "0 0 2 * * ?", true, false,
                null, null, null, null, null, null);

        ScheduleRunPreviewResponse p = executionService.previewDraft(off);

        assertFalse(p.enabled());
        assertEquals("common", p.policyName());
    }

    // ── 검증 ───────────────────────────────────────────────────

    @DisplayName("스케줄명·실행 주기가 없어도 오류가 없고 전력 상한은 건수에 닿지 않는다")
    @Test
    void name_cron_and_power_budget_are_ignored() {
        policyRepository.save(active("common", null));
        workloadRepository.save(typed("v1", Workload.ResourceType.VM));
        ScheduleRequest form = new ScheduleRequest(null, null, Workload.ResourceType.VM, "not a cron", null, null,
                null, null, null, 1.0, null, null);

        ScheduleRunPreviewResponse p = executionService.previewDraft(form);

        assertEquals(1, p.targetCount(), "전력 상한 1W 는 건수에 닿지 않는다");
    }

    @DisplayName("공백뿐인 스케줄 ID 는 null 로 돌아온다")
    @Test
    void blank_schedule_id_becomes_null() {
        policyRepository.save(active("common", null));
        ScheduleRequest blank = new ScheduleRequest("   ", null, null, null, null, null, null, null, null, null, null, null);

        assertNull(executionService.previewDraft(blank).scheduleId());
    }

    @DisplayName("공백뿐인 엔진은 미지정으로 보아 정책 기본 엔진, 없으면 서버 기본 엔진을 쓴다")
    @Test
    void blank_engine_inherits_policy_or_server_default() {
        SchedulingPolicy withDefault = active("with-default", null);
        withDefault.setDefaultEngine("temperature");
        withDefault = policyRepository.save(withDefault);
        SchedulingPolicy without = policyRepository.save(active("without-default", null));

        ScheduleRunPreviewResponse fromPolicy = executionService.previewDraft(
                form(Workload.ResourceType.VM, "  ", withDefault.getId(), null, null));
        ScheduleRunPreviewResponse fromServer = executionService.previewDraft(
                form(Workload.ResourceType.VM, "  ", without.getId(), null, null));

        assertEquals("temperature", fromPolicy.engine());
        assertEquals(EngineSource.POLICY, fromPolicy.engineSource());
        assertEquals("weighted", fromServer.engine());
        assertEquals(EngineSource.DEFAULT, fromServer.engineSource());
    }

    @DisplayName("본문이 없으면 400 이다")
    @Test
    void null_body_is_400() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> executionService.previewDraft(null));
    }

    @DisplayName("엔진·배치 순서·설명은 생성과 같은 규칙으로 400 이다")
    @Test
    void engine_order_and_description_validated_like_create() {
        policyRepository.save(active("common", null));
        BusinessException engine = assertThrows(BusinessException.class, () -> executionService.previewDraft(
                form(Workload.ResourceType.VM, "no-such-engine", null, null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, engine.getStatus());
        assertEquals("알 수 없는 스케줄링 엔진입니다: no-such-engine", engine.getMessage());
        assertStatus(HttpStatus.BAD_REQUEST, () -> executionService.previewDraft(
                form(Workload.ResourceType.VM, null, null, null, "random")));
        ScheduleRequest longDescription = new ScheduleRequest(null, null, Workload.ResourceType.VM, null, null, null,
                "x".repeat(Schedule.DESCRIPTION_MAX + 1), null, null, null, null, null);
        assertStatus(HttpStatus.BAD_REQUEST, () -> executionService.previewDraft(longDescription));
    }

    @DisplayName("없는 정책 id 는 저장된 미리보기와 같은 404 와 문구다")
    @Test
    void missing_policy_is_404_like_saved_preview() {
        policyRepository.save(active("common", null));
        ScheduleRequest form = form(Workload.ResourceType.VM, null, 999_999_999L, null, null);
        Long savedId = scheduleService.create(form).id();
        BusinessException fromSaved = assertThrows(BusinessException.class, () -> executionService.preview(savedId));

        BusinessException draft = assertThrows(BusinessException.class, () -> executionService.previewDraft(form));

        assertEquals(HttpStatus.NOT_FOUND, draft.getStatus());
        assertEquals(fromSaved.getMessage(), draft.getMessage());
    }

    @DisplayName("저장된 스케줄 미리보기는 그대로다 — 종류별 정책과 건수")
    @Test
    void saved_preview_still_works() {
        policyRepository.save(active("common", null));
        workloadRepository.save(typed("v1", Workload.ResourceType.VM));
        Long savedId = scheduleService.create(form(Workload.ResourceType.VM, null, null, null, null)).id();

        ScheduleRunPreviewResponse p = executionService.preview(savedId);

        assertEquals(savedId, p.id());
        assertEquals(1, p.targetCount());
        assertEquals(PolicyChoice.Source.COMMON, p.policySource());
    }

    // ── 도우미 ─────────────────────────────────────────────────

    private static void assertStatus(HttpStatus expected, Runnable call) {
        BusinessException e = assertThrows(BusinessException.class, call::run);
        assertEquals(expected, e.getStatus(), e.getMessage());
    }

    private static SchedulingPolicy active(String name, Workload.ResourceType type) {
        SchedulingPolicy p = new SchedulingPolicy(name, name);
        p.setResourceType(type);
        p.setActive(true);
        return p;
    }

    private static Workload typed(String name, Workload.ResourceType type) {
        Workload w = new Workload(name, 1, 1024);
        w.setResourceType(type);
        return w;
    }

    private static Workload failedTimes(Workload w, int times) {
        for (int i = 0; i < times; i++) w.markFailed("자원 부족");
        return w;
    }
}

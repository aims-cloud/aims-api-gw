package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
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
import com.innogrid.aims.scheduler.dto.ScheduleRunPreviewResponse;
import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.EmptyReason;
import com.innogrid.aims.scheduler.model.EngineSource;
import com.innogrid.aims.scheduler.model.PolicyChoice;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * 스케줄 실행 미리보기 — 실행과 같은 정책·엔진·건수·문구를 저장 없이 돌려준다.
 *
 * <p>테스트 환경에는 메트릭 저장소가 없어 실제 실행은 "활성 노드 없음" 으로 끝나지만, 미리보기가 보는 것은 실행
 * 직전까지의 해석(정책·엔진·대상 선정)이라 그 부분은 결정적으로 대조할 수 있다.</p>
 */
@SpringBootTest
@Transactional
class ScheduleRunPreviewTest {

    @Autowired private ScheduleExecutionService executionService;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private ScheduleRunRepository runRepository;
    @Autowired private WorkloadRepository workloadRepository;
    @Autowired private SchedulingPolicyRepository policyRepository;
    @Autowired private SchedulingHistoryRepository historyRepository;
    @Autowired private SchedulerProperties properties;
    @MockitoBean private NodeResourceCollector collector;

    @BeforeEach
    void clean() {
        runRepository.deleteAll();
        scheduleRepository.deleteAll();
        workloadRepository.deleteAll();
        // 트랜잭션 안에서 INSERT 가 DELETE 보다 먼저 나가면 같은 이름 저장이 유니크에 걸린다 — 삭제를 즉시 반영한다.
        policyRepository.deleteAll();
        policyRepository.flush();
        // 테스트 환경에는 메트릭 저장소가 없다 — 실제 실행이 부르는 수집은 빈 스냅샷으로 답한다(미리보기는 부르지 않아야 한다)
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(), List.of()));
    }

    // ── 건수 ───────────────────────────────────────────────────

    @DisplayName("대상·대기·소진 세 건수를 따로 세고 대상이 있으면 빈 사유는 없다")
    @Test
    void preview_counts_targets_waiting_exhausted() {
        policyRepository.save(active("common", null));
        workloadRepository.save(vm("대상1"));
        workloadRepository.save(vm("대상2"));
        workloadRepository.save(failedTimes(vm("대기"), 1));                                 // 간격 안 → 대기
        workloadRepository.save(failedTimes(vm("소진"), properties.getMaxRetryCount()));    // 상한 → 소진
        Schedule schedule = save(scheduleOf("count"));

        ScheduleRunPreviewResponse p = executionService.preview(schedule.getId());

        assertEquals(2, p.targetCount());
        assertEquals(1, p.waitingCount());
        assertEquals(1, p.exhaustedCount());
        assertEquals(properties.getMaxWorkloadsPerRun(), p.runLimit());
        assertNull(p.emptyReason(), "대상이 있으면 사유 없음");
        assertNull(p.emptyMessage());
        assertEquals(Workload.ResourceType.VM, p.resourceType(), "종류를 비운 스케줄은 VM 으로 해석");
        assertEquals(schedule.getId(), p.id());
        assertEquals("count", p.scheduleId());
        assertTrue(p.enabled());
    }

    @DisplayName("대상 0건의 사유와 문구가 실행이 남기는 메시지와 글자 단위로 같다")
    @Test
    void preview_empty_reason_and_message_match_run() {
        policyRepository.save(active("common", null));

        // 없음
        Schedule none = save(scheduleOf("none"));
        ScheduleRunPreviewResponse pNone = executionService.preview(none.getId());
        assertEquals(EmptyReason.NONE, pNone.emptyReason());
        assertEquals(executionService.runNow(none.getId(), "tester").getMessage(), pNone.emptyMessage());
        assertEquals("대상 없음", pNone.emptyMessage());

        // 대기 — 방금 실패해 간격 안
        workloadRepository.save(failedTimes(vm("대기"), 1));
        Schedule waiting = save(scheduleOf("waiting"));
        ScheduleRunPreviewResponse pWait = executionService.preview(waiting.getId());
        assertEquals(EmptyReason.WAITING, pWait.emptyReason());
        assertEquals(1, pWait.waitingCount());
        assertEquals(executionService.runNow(waiting.getId(), "tester").getMessage(), pWait.emptyMessage());
        assertTrue(pWait.emptyMessage().contains("재시도 대기 1건"), pWait.emptyMessage());

        // 소진 — 대기와 함께 있어도 소진이 우선(실행과 같은 순서)
        workloadRepository.save(failedTimes(vm("소진"), properties.getMaxRetryCount()));
        Schedule exhausted = save(scheduleOf("exhausted"));
        ScheduleRunPreviewResponse pEx = executionService.preview(exhausted.getId());
        assertEquals(EmptyReason.EXHAUSTED, pEx.emptyReason());
        assertEquals(1, pEx.exhaustedCount());
        assertEquals(1, pEx.waitingCount(), "미리보기는 대기도 함께 센다");
        ScheduleRun run = executionService.runNow(exhausted.getId(), "tester");
        assertEquals(run.getMessage(), pEx.emptyMessage());
        assertEquals(ScheduleRun.RunStatus.FAILED, run.getStatus(), "소진은 실행이 실패로 기록");
    }

    @DisplayName("예약 취소로 대기로 돌아간 워크로드도 마지막 실패 뒤 간격 안이면 재시도 대기로 센다")
    @Test
    void released_workload_within_backoff_counts_as_waiting() {
        policyRepository.save(active("common", null));
        Workload w = failedTimes(vm("취소"), 1);                  // 간격 안의 실패 기록
        w.assignTo("mdc-01", "n1:9100", 0.5, "배치");            // 배치됐다가
        assertTrue(w.release("예약 취소"));                       // 예약 취소로 대기 — 마지막 실패 시각은 남는다
        workloadRepository.save(w);
        Schedule schedule = save(scheduleOf("released"));

        ScheduleRunPreviewResponse p = executionService.preview(schedule.getId());

        assertEquals(Workload.WorkloadStatus.PENDING, w.getStatus());
        assertNotNull(w.getLastFailedAt(), "예약 취소는 마지막 실패 시각을 지우지 않는다");
        assertEquals(0, p.targetCount(), "간격 안이라 대상이 아니다");
        assertEquals(1, p.waitingCount(), "대기 상태라도 간격 안이면 재시도 대기로 센다");
        assertEquals(EmptyReason.WAITING, p.emptyReason());
        assertTrue(p.emptyMessage().contains("재시도 대기 1건"), p.emptyMessage());
    }

    // ── 정책 · 엔진 ────────────────────────────────────────────

    @DisplayName("정책과 엔진이 실행 이력의 값과 같고 고른 단계가 정확하다")
    @Test
    void preview_policy_and_engine_match_run() {
        SchedulingPolicy common = policyRepository.save(active("common", null));
        SchedulingPolicy gpu = active("gpu-only", Workload.ResourceType.GPU);
        gpu.setDefaultEngine("power");
        policyRepository.save(gpu);
        SchedulingPolicy pinned = active("pinned", null);
        pinned.setActive(false);
        pinned = policyRepository.save(pinned);

        // ② 종류별 활성 + 정책 기본 엔진
        Schedule byType = scheduleOf("by-type");
        byType.setResourceType(Workload.ResourceType.GPU);
        byType = save(byType);
        ScheduleRunPreviewResponse p1 = executionService.preview(byType.getId());
        assertEquals("gpu-only", p1.policyName());
        assertEquals(PolicyChoice.Source.RESOURCE_TYPE, p1.policySource());
        assertEquals("power", p1.engine());
        assertEquals(EngineSource.POLICY, p1.engineSource());
        ScheduleRun r1 = executionService.runNow(byType.getId(), "tester");
        assertEquals(r1.getPolicyName(), p1.policyName());
        assertEquals(r1.getEngineId(), p1.engine());

        // ③ 공통 활성 + 서버 기본 엔진
        Schedule common1 = save(scheduleOf("common"));
        ScheduleRunPreviewResponse p2 = executionService.preview(common1.getId());
        assertEquals(common.getName(), p2.policyName());
        assertEquals(PolicyChoice.Source.COMMON, p2.policySource());
        assertEquals("weighted", p2.engine());
        assertEquals(EngineSource.DEFAULT, p2.engineSource());
        ScheduleRun r2 = executionService.runNow(common1.getId(), "tester");
        assertEquals(r2.getPolicyName(), p2.policyName());
        assertEquals(r2.getEngineId(), p2.engine());

        // ① 스케줄 지정(비활성이어도 지정이 이김) + 스케줄 지정 엔진
        Schedule pinnedSchedule = scheduleOf("pinned");
        pinnedSchedule.setPolicyId(pinned.getId());
        pinnedSchedule.setEngine("weighted");
        pinnedSchedule = save(pinnedSchedule);
        ScheduleRunPreviewResponse p3 = executionService.preview(pinnedSchedule.getId());
        assertEquals("pinned", p3.policyName());
        assertEquals(PolicyChoice.Source.SCHEDULE, p3.policySource());
        assertEquals(EngineSource.SCHEDULE, p3.engineSource());
        ScheduleRun r3 = executionService.runNow(pinnedSchedule.getId(), "tester");
        assertEquals(r3.getPolicyName(), p3.policyName());
        assertEquals(r3.getEngineId(), p3.engine());
    }

    @DisplayName("정책이 하나도 없으면 default 를 보여 주되 저장하지 않고 이어지는 실행은 저장한다")
    @Test
    void preview_does_not_save_default_policy() {
        assertEquals(0, policyRepository.count());
        Schedule schedule = save(scheduleOf("no-policy"));

        ScheduleRunPreviewResponse p = executionService.preview(schedule.getId());

        assertEquals(SchedulingService.DEFAULT_POLICY_NAME, p.policyName());
        assertEquals(PolicyChoice.Source.DEFAULT, p.policySource());
        assertEquals(0, policyRepository.count(), "읽기 요청이 행을 만들지 않는다");
        assertTrue(policyRepository.findByName(SchedulingService.DEFAULT_POLICY_NAME).isEmpty());

        executionService.runNow(schedule.getId(), "tester");
        assertTrue(policyRepository.findByName(SchedulingService.DEFAULT_POLICY_NAME).isPresent(),
                "실행은 예전처럼 default 를 만들어 저장한다(현행 유지)");
    }

    @DisplayName("비활성 default 만 있으면 그 값을 보여 주고 다시 저장하지 않는다")
    @Test
    void preview_reuses_inactive_default() {
        SchedulingPolicy off = new SchedulingPolicy(SchedulingService.DEFAULT_POLICY_NAME, "기본");
        off.setActive(false);
        policyRepository.save(off);
        Schedule schedule = save(scheduleOf("inactive-default"));

        ScheduleRunPreviewResponse p = executionService.preview(schedule.getId());

        assertEquals(PolicyChoice.Source.DEFAULT, p.policySource());
        assertEquals(1, policyRepository.count());
        assertFalse(policyRepository.findByName(SchedulingService.DEFAULT_POLICY_NAME).orElseThrow().isActive());
    }

    // ── 부작용 없음 ────────────────────────────────────────────

    @DisplayName("미리보기는 실행 이력·스케줄 최근 실행 정보·워크로드·배치 이력을 바꾸지 않는다")
    @Test
    void preview_writes_nothing() {
        policyRepository.save(active("common", null));
        Workload w = workloadRepository.save(vm("대상"));
        Schedule schedule = save(scheduleOf("silent"));
        long runs = runRepository.count();
        long histories = historyRepository.count();

        executionService.preview(schedule.getId());
        executionService.preview(schedule.getId());

        Schedule reloaded = scheduleRepository.findById(schedule.getId()).orElseThrow();
        assertEquals(runs, runRepository.count());
        assertEquals(histories, historyRepository.count());
        assertNull(reloaded.getLastRunAt());
        assertNull(reloaded.getLastRunStatus());
        assertNull(reloaded.getLastRunMessage());
        Workload wr = workloadRepository.findById(w.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.PENDING, wr.getStatus());
        assertNull(wr.getRetryCount());
        // 실행 권한을 잡지 않았으므로 바로 실행할 수 있다
        assertDoesNotThrow(() -> executionService.runNow(schedule.getId(), "tester"));
    }

    @DisplayName("미리보기는 노드를 모으지 않는다")
    @Test
    void preview_does_not_collect_nodes() {
        policyRepository.save(active("common", null));
        workloadRepository.save(vm("대상"));
        Schedule schedule = save(scheduleOf("no-collect"));

        executionService.preview(schedule.getId());

        verify(collector, never()).collect();
    }

    @DisplayName("실행 중으로 표시된 스케줄도 미리보기는 200 이다")
    @Test
    void preview_running_schedule_answers_200() {
        policyRepository.save(active("common", null));
        Schedule schedule = scheduleOf("busy");
        schedule.recordRun(ScheduleRun.RunStatus.RUNNING, null, null, null);
        Schedule saved = save(schedule);

        ScheduleRunPreviewResponse p = assertDoesNotThrow(() -> executionService.preview(saved.getId()));

        assertEquals(EmptyReason.NONE, p.emptyReason());
        assertEquals(ScheduleRun.RunStatus.RUNNING,
                scheduleRepository.findById(saved.getId()).orElseThrow().getLastRunStatus(), "실행 표시를 건드리지 않는다");
    }

    @DisplayName("온도 한계를 지정한 스케줄도 정책 이름과 출처는 원본 정책 그대로다")
    @Test
    void preview_with_temperature_override_keeps_policy_identity() {
        policyRepository.save(active("common", null));
        Schedule schedule = scheduleOf("temp");
        schedule.setMaxTemperatureCelsius(70.0);
        Schedule saved = save(schedule);

        ScheduleRunPreviewResponse p = executionService.preview(saved.getId());

        assertEquals("common", p.policyName());
        assertEquals(PolicyChoice.Source.COMMON, p.policySource());
        assertEquals(1, policyRepository.count(), "덮어쓴 사본은 저장되지 않는다");
    }

    @DisplayName("종류를 비운 워크로드는 VM 스케줄에만 집계되고 GPU 스케줄에는 세지 않는다")
    @Test
    void preview_counts_null_type_only_for_vm() {
        policyRepository.save(active("common", null));
        workloadRepository.save(new Workload("종류없음", 1, 1024));
        workloadRepository.save(failedTimes(new Workload("종류없음-소진", 1, 1024), properties.getMaxRetryCount()));
        Schedule vm = save(scheduleOf("vm"));
        Schedule gpu = scheduleOf("gpu");
        gpu.setResourceType(Workload.ResourceType.GPU);
        gpu = save(gpu);

        ScheduleRunPreviewResponse pVm = executionService.preview(vm.getId());
        ScheduleRunPreviewResponse pGpu = executionService.preview(gpu.getId());

        assertEquals(1, pVm.targetCount());
        assertEquals(1, pVm.exhaustedCount());
        assertEquals(0, pGpu.targetCount());
        assertEquals(0, pGpu.exhaustedCount());
        assertEquals(EmptyReason.NONE, pGpu.emptyReason());
    }

    @DisplayName("대상 0건 문구 세 가지는 설정값으로 조립된 전문과 같다")
    @Test
    void empty_messages_full_text() {
        // 3장 주의 6·7 이 이 전문을 인용한다 — 바꾸면 계약 문서도 함께 고칠 것
        ScheduleExecutionService.EmptyTargetOutcome none = executionService.outcomeOf(0, () -> 0);
        ScheduleExecutionService.EmptyTargetOutcome waiting = executionService.outcomeOf(0, () -> 2);
        ScheduleExecutionService.EmptyTargetOutcome exhausted = executionService.outcomeOf(3, () -> {
            throw new AssertionError("소진이 있으면 대기를 세지 않는다");
        });

        assertEquals("대상 없음", none.message());
        assertEquals(ScheduleRun.RunStatus.COMPLETED, none.status());
        assertEquals("대상 없음 (재시도 대기 2건 — " + properties.getRetryBackoffMinutes() + "분 뒤 다시 시도합니다)", waiting.message());
        assertEquals(ScheduleRun.RunStatus.COMPLETED, waiting.status());
        assertEquals("대상 없음 (재시도 소진 3건 — 일괄 재개가 필요합니다)", exhausted.message());
        assertEquals(ScheduleRun.RunStatus.FAILED, exhausted.status());
        assertEquals(EmptyReason.EXHAUSTED, exhausted.reason());
    }

    // ── 오류 · 게이트 ──────────────────────────────────────────

    @DisplayName("없는 스케줄은 404 이다")
    @Test
    void preview_missing_schedule_404() {
        BusinessException e = assertThrows(BusinessException.class, () -> executionService.preview(999_999L));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @DisplayName("스케줄이 지정한 정책이 없으면 실행과 같은 404 이다")
    @Test
    void preview_missing_policy_404() {
        Schedule schedule = scheduleOf("dangling");
        schedule.setPolicyId(999_999L);
        Schedule saved = save(schedule);

        BusinessException e = assertThrows(BusinessException.class, () -> executionService.preview(saved.getId()));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        assertTrue(e.getMessage().contains("지정된 배치 정책"), e.getMessage());
    }

    @DisplayName("사용하지 않는 스케줄도 미리보기는 200 이고 enabled 가 false 로 실린다")
    @Test
    void preview_disabled_schedule_answers_200() {
        policyRepository.save(active("common", null));
        Schedule schedule = scheduleOf("off");
        schedule.setEnabled(false);
        Schedule saved = save(schedule);

        ScheduleRunPreviewResponse p = assertDoesNotThrow(() -> executionService.preview(saved.getId()));

        assertFalse(p.enabled());
        assertEquals(EmptyReason.NONE, p.emptyReason());
    }

    @DisplayName("대상이 상한을 넘어도 상한 적용 전 건수를 그대로 알려 준다")
    @Test
    void preview_target_over_limit_reports_full_count() {
        policyRepository.save(active("common", null));
        int limit = properties.getMaxWorkloadsPerRun();
        properties.setMaxWorkloadsPerRun(2);
        try {
            workloadRepository.save(vm("a"));
            workloadRepository.save(vm("b"));
            workloadRepository.save(vm("c"));
            Schedule schedule = save(scheduleOf("over"));

            ScheduleRunPreviewResponse p = executionService.preview(schedule.getId());

            assertEquals(3, p.targetCount());
            assertEquals(2, p.runLimit());
        } finally {
            properties.setMaxWorkloadsPerRun(limit);
        }
    }

    // ── 도우미 ─────────────────────────────────────────────────

    private static SchedulingPolicy active(String name, Workload.ResourceType type) {
        SchedulingPolicy p = new SchedulingPolicy(name, name);
        p.setResourceType(type);
        p.setActive(true);
        return p;
    }

    private static Workload vm(String name) {
        Workload w = new Workload(name, 1, 1024);
        w.setResourceType(Workload.ResourceType.VM);
        return w;
    }

    /** {@code times} 번 실패한 워크로드. 마지막 실패는 지금이라 재시도 간격 안이다. */
    private static Workload failedTimes(Workload w, int times) {
        for (int i = 0; i < times; i++) {
            w.markFailed("자원 부족");
        }
        assertNotNull(w.getLastFailedAt());
        assertTrue(w.getLastFailedAt().isAfter(OffsetDateTime.now().minusMinutes(1)));
        return w;
    }

    private Schedule scheduleOf(String key) {
        Schedule schedule = new Schedule(key, "테스트 " + key, "0 0 2 * * ?");
        schedule.setEnabled(true);
        schedule.setAutoRun(false);
        return schedule;
    }

    private Schedule save(Schedule schedule) {
        return scheduleRepository.save(schedule);
    }
}

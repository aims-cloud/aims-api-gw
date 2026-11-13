package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dispatch.DispatcherRegistry;
import com.innogrid.aims.scheduler.dispatch.PlacementDispatcher;
import com.innogrid.aims.scheduler.dispatch.RecordingPlacementDispatcher;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.WorkloadUpdateRequest;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * 스케줄 실행이 대상을 읽은 뒤 바뀐 워크로드를 배치가 어떻게 다루는가.
 *
 * <p>실행은 대상 워크로드를 시작 때 한 번 읽고(트랜잭션 밖이라 분리된 사본), 노드를 모으고 앞 워크로드를 배치·전달하는
 * 동안 그 사본을 들고 있다. 여기서는 노드 수집 대역의 첫 호출(대상 조회 뒤 · 배치 루프 앞)이나 전달 대역 안에서 편집·
 * 되살리기·삭제·다른 배치를 자기 트랜잭션으로 커밋해, 그 창을 스레드 없이 재현한다.</p>
 *
 * <pre>
 * 노드      8코어 1대, CPU 10% → 가용 7.2코어 (저장 실패 테스트만 CPU 1% — 가동 노드 0 에서 시작)
 * 워크로드  NPU 종류(다른 테스트가 적게 쓰는 종류) · 이름 접두사로 정리
 * 디스패처  기록형(수락)
 * </pre>
 *
 * <p><b>이 클래스는 {@code @Transactional} 을 쓰지 않는다.</b> 커밋된 값을 다시 읽어 확인한다.</p>
 */
@SpringBootTest
class WorkloadChangeDuringRunTest {

    private static final String PREFIX = "wcd-";
    private static final String NODE = "wcd-n8:9100";

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired WorkloadService workloadService;
    @Autowired SchedulingService schedulingService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean NodeResourceCollector collector;
    @MockitoBean DispatcherRegistry registry;
    @MockitoSpyBean WeightedScoreScheduler weighted;

    @AfterEach
    void clean() {
        historyRepository.findAll().stream().filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith(PREFIX)).forEach(historyRepository::delete);
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith(PREFIX)).forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith(PREFIX)).forEach(s -> scheduleService.delete(List.of(s.getId())));
    }

    // ── 편집 ─────────────────────────────────────────────────────────────

    @DisplayName("실행이 대상을 읽은 뒤 커밋한 편집은 배치에 반영되고 남는다")
    @Test
    void edit_after_target_lookup_is_used_and_kept() {
        Workload first = save(PREFIX + "edit-a", 1, Workload.WorkloadPriority.HIGH);
        Workload second = save(PREFIX + "edit-2", 1, Workload.WorkloadPriority.NORMAL);
        nodeThen(10, () -> workloadService.update(first.getId(), body("{\"name\":\"" + PREFIX + "edit-b\",\"requiredCpu\":7}")));

        ScheduleRun run = executionService.runNow(schedule(PREFIX + "s-edit"), "tester");

        Workload w1 = reload(first);
        assertEquals(Workload.WorkloadStatus.SCHEDULED, w1.getStatus());
        assertEquals(PREFIX + "edit-b", w1.getName(), "편집한 이름이 배치 저장으로 되돌아가면 안 된다");
        assertEquals(7, w1.getRequiredCpu(), "편집한 요구량이 남는다");
        assertNotEquals(Workload.WorkloadStatus.SCHEDULED, reload(second).getStatus(),
                "편집한 7코어로 예약했으니 남은 0.2코어로는 다음 워크로드가 못 들어간다");
        assertEquals(1, run.getSuccessCount());
        assertEquals(1, run.getFailedCount());
    }

    @DisplayName("앞 워크로드를 배치하는 사이의 편집도 다음 배치에 반영된다")
    @Test
    void edit_during_earlier_placement_is_used_for_later_workload() {
        save(PREFIX + "mid-1", 1, Workload.WorkloadPriority.HIGH);
        Workload second = save(PREFIX + "mid-2", 1, Workload.WorkloadPriority.NORMAL);
        nodeThen(10, () -> { });
        when(registry.current()).thenReturn(new PlacementDispatcher() {
            private final PlacementDispatcher recording = new RecordingPlacementDispatcher();
            @Override public String getMode() { return recording.getMode(); }
            @Override public Outcome dispatch(Request request) {
                if ((PREFIX + "mid-1").equals(request.workloadName())) {
                    workloadService.update(second.getId(), body("{\"requiredCpu\":7}"));
                }
                return recording.dispatch(request);
            }
        });

        executionService.runNow(schedule(PREFIX + "s-mid"), "tester");

        Workload w2 = reload(second);
        assertEquals(7, w2.getRequiredCpu(), "앞 배치 중 편집한 요구량이 남는다");
        assertNotEquals(Workload.WorkloadStatus.SCHEDULED, w2.getStatus(),
                "앞 배치가 1코어를 잡아 남은 6.2코어로는 편집한 7코어가 못 들어간다 — 워크로드마다 다시 읽는다");
    }

    // ── 되살리기 ──────────────────────────────────────────────────────────

    @DisplayName("실행이 대상을 읽은 뒤 되살린 워크로드는 재시도 횟수가 0 으로 남는다")
    @Test
    void revived_after_target_lookup_keeps_cleared_retry_count() {
        Workload failed = new Workload(PREFIX + "revive", 1, 64);
        failed.setResourceType(Workload.ResourceType.NPU);
        failed.markFailed("앞선 실패 1");
        failed.markFailed("앞선 실패 2");
        ReflectionTestUtils.setField(failed, "lastFailedAt", OffsetDateTime.now().minusMinutes(20));   // 재시도 간격이 지났다
        Workload saved = workloadRepository.saveAndFlush(failed);
        nodeThen(10, () -> schedulingService.changeStatus(saved.getId(), Workload.WorkloadStatus.PENDING));

        executionService.runNow(schedule(PREFIX + "s-revive"), "tester");

        Workload after = reload(saved);
        assertEquals(Workload.WorkloadStatus.SCHEDULED, after.getStatus());
        assertEquals(0, after.resolveRetryCount(), "되살리기가 지운 재시도 횟수가 배치 저장으로 되돌아가면 안 된다");
        assertNull(after.getLastFailedAt());
    }

    // ── 삭제 ─────────────────────────────────────────────────────────────

    @DisplayName("실행이 대상을 읽은 뒤 지운 워크로드는 건너뛰고 회차는 계속된다")
    @Test
    void deleted_after_target_lookup_is_skipped_and_run_continues() {
        Workload gone = save(PREFIX + "del-1", 1, Workload.WorkloadPriority.HIGH);
        Workload kept = save(PREFIX + "del-2", 1, Workload.WorkloadPriority.NORMAL);
        nodeThen(10, () -> workloadService.delete(gone.getId()));

        ScheduleRun run = assertDoesNotThrow(() -> executionService.runNow(schedule(PREFIX + "s-del"), "tester"));

        assertEquals(ScheduleRun.RunStatus.COMPLETED, run.getStatus());
        assertEquals(1, run.getSuccessCount());
        assertEquals(1, run.getFailedCount(), "건너뛴 건은 실패로 센다");
        assertTrue(workloadRepository.findById(gone.getId()).isEmpty(), "지운 워크로드가 되살아나면 안 된다");
        assertTrue(workloadRepository.findAll().stream().noneMatch(w -> (PREFIX + "del-1").equals(w.getName())), "새 행으로도 생기지 않는다");
        assertTrue(dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(gone.getId()).isEmpty(), "전달 기록도 없다");
        List<SchedulingHistory> skipped = historiesOf(run, gone.getId());
        assertEquals(1, skipped.size(), "결정 목록에 건너뜀 한 건");
        assertFalse(skipped.get(0).getSuccess());
        assertTrue(skipped.get(0).getReason().contains("삭제됨"), skipped.get(0).getReason());
        assertEquals(Workload.WorkloadStatus.SCHEDULED, reload(kept).getStatus(), "다음 워크로드는 배치된다");
    }

    // ── 다른 경로의 배치 ───────────────────────────────────────────────────

    @DisplayName("실행이 대상을 읽은 뒤 다른 경로로 배치된 워크로드는 다시 배치하지 않는다")
    @Test
    void placed_elsewhere_after_target_lookup_is_not_placed_again() {
        Workload target = save(PREFIX + "else", 1, Workload.WorkloadPriority.NORMAL);
        Workload[] afterHook = new Workload[1];
        nodeThen(10, () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Workload w = workloadRepository.findById(target.getId()).orElseThrow();
                w.assignTo("mdc-02", "other-node:9100", 0.5, "다른 경로의 배치");
                workloadRepository.saveAndFlush(w);
            });
            afterHook[0] = workloadRepository.findById(target.getId()).orElseThrow();   // DB 에 저장된 값(시각 반올림 뒤)
        });

        ScheduleRun run = executionService.runNow(schedule(PREFIX + "s-else"), "tester");

        Workload after = reload(target);
        assertEquals(Workload.WorkloadStatus.SCHEDULED, after.getStatus());
        assertEquals("other-node:9100", after.getAssignedNodeId(), "다른 경로의 배정이 덮이면 안 된다");
        assertEquals(afterHook[0].getScheduledAt().toInstant(), after.getScheduledAt().toInstant(), "배정 시각도 그대로");
        assertTrue(dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(target.getId()).isEmpty(), "이 회차가 전달하지 않는다");
        List<SchedulingHistory> histories = historiesOf(run, target.getId());
        assertEquals(1, histories.size());
        assertFalse(histories.get(0).getSuccess());
        assertTrue(histories.get(0).getReason().contains("SCHEDULED"), histories.get(0).getReason());
    }

    // ── 겹친 배치 ────────────────────────────────────────────────────────

    @DisplayName("겹친 다른 스케줄 실행이 배치하는 사이 먼저 배치한 워크로드는 덮지 않고 건너뛴다")
    @Test
    void overlapping_run_does_not_overwrite_placement_committed_while_placing() {
        Workload target = save(PREFIX + "ovl", 1, Workload.WorkloadPriority.NORMAL);
        nodeThen(10, () -> { });
        long other = schedule(PREFIX + "s-ovl-b");
        Workload[] afterOther = new Workload[1];
        elsewhereWhilePlacing(() -> {
            executionService.runNow(other, "tester");
            afterOther[0] = workloadRepository.findById(target.getId()).orElseThrow();
        }, false);

        ScheduleRun run = executionService.runNow(schedule(PREFIX + "s-ovl-a"), "tester");

        Workload after = reload(target);
        assertEquals(Workload.WorkloadStatus.SCHEDULED, after.getStatus());
        assertEquals(afterOther[0].getScheduledAt().toInstant(), after.getScheduledAt().toInstant(), "먼저 커밋한 실행의 배정 시각이 덮이면 안 된다");
        assertEquals(1, dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(target.getId()).size(),
                "실행 계층에 같은 워크로드를 두 번 전달하지 않는다");
        assertEquals(0, run.getSuccessCount());
        List<SchedulingHistory> histories = historiesOf(run, target.getId());
        assertEquals(1, histories.size(), "결정 목록에 건너뜀 한 건");
        assertFalse(histories.get(0).getSuccess());
        assertTrue(histories.get(0).getReason().contains("배치하는 사이 상태가 SCHEDULED"), histories.get(0).getReason());
    }

    @DisplayName("배치하는 사이 다른 실행이 먼저 배치하면 이 실행의 실패 결정이 그 배치를 되돌리지 않는다")
    @Test
    void failed_decision_does_not_revert_placement_committed_while_placing() {
        Workload target = save(PREFIX + "ovl-fail", 1, Workload.WorkloadPriority.NORMAL);
        nodeThen(10, () -> { });
        long other = schedule(PREFIX + "s-ovlf-b");
        elsewhereWhilePlacing(() -> executionService.runNow(other, "tester"), true);

        ScheduleRun run = executionService.runNow(schedule(PREFIX + "s-ovlf-a"), "tester");

        Workload after = reload(target);
        assertEquals(Workload.WorkloadStatus.SCHEDULED, after.getStatus(), "먼저 커밋한 배치가 실패로 덮이면 안 된다");
        assertEquals(NODE, after.getAssignedNodeId());
        assertEquals(0, after.resolveRetryCount(), "재시도 횟수를 올리지 않는다");
        assertEquals(1, dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(target.getId()).size());
        List<SchedulingHistory> histories = historiesOf(run, target.getId());
        assertEquals(1, histories.size());
        assertTrue(histories.get(0).getReason().contains("배치하는 사이"), histories.get(0).getReason());
    }

    @DisplayName("배치하는 사이 단건 배치가 먼저 끝난 워크로드는 덮지 않고 건너뛴다")
    @Test
    void single_placement_committed_while_placing_is_not_overwritten() {
        Workload target = save(PREFIX + "ovl-one", 1, Workload.WorkloadPriority.NORMAL);
        nodeThen(10, () -> { });
        Workload[] afterOther = new Workload[1];
        elsewhereWhilePlacing(() -> {
            schedulingService.scheduleWorkload(target.getId(), null);
            afterOther[0] = workloadRepository.findById(target.getId()).orElseThrow();
        }, false);

        ScheduleRun run = executionService.runNow(schedule(PREFIX + "s-ovl-one"), "tester");

        Workload after = reload(target);
        assertEquals(Workload.WorkloadStatus.SCHEDULED, after.getStatus());
        assertEquals(afterOther[0].getScheduledAt().toInstant(), after.getScheduledAt().toInstant(), "단건 배치의 배정 시각이 덮이면 안 된다");
        assertEquals(1, dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(target.getId()).size());
        List<SchedulingHistory> histories = historiesOf(run, target.getId());
        assertEquals(1, histories.size());
        assertFalse(histories.get(0).getSuccess());
        assertTrue(histories.get(0).getReason().contains("배치하는 사이 상태가 SCHEDULED"), histories.get(0).getReason());
    }

    @DisplayName("배치하는 사이 지운 워크로드는 건너뛰고 회차는 계속된다")
    @Test
    void deleted_while_placing_is_skipped_and_run_continues() {
        Workload gone = save(PREFIX + "ovl-del-1", 1, Workload.WorkloadPriority.HIGH);
        Workload kept = save(PREFIX + "ovl-del-2", 1, Workload.WorkloadPriority.NORMAL);
        nodeThen(10, () -> { });
        elsewhereWhilePlacing(() -> workloadService.delete(gone.getId()), false);

        ScheduleRun run = assertDoesNotThrow(() -> executionService.runNow(schedule(PREFIX + "s-ovl-del"), "tester"));

        assertEquals(ScheduleRun.RunStatus.COMPLETED, run.getStatus());
        assertEquals(1, run.getSuccessCount());
        assertEquals(1, run.getFailedCount(), "건너뛴 건은 실패로 센다");
        assertTrue(workloadRepository.findById(gone.getId()).isEmpty(), "지운 워크로드가 되살아나면 안 된다");
        assertTrue(workloadRepository.findAll().stream().noneMatch(w -> (PREFIX + "ovl-del-1").equals(w.getName())), "새 행으로도 생기지 않는다");
        List<SchedulingHistory> skipped = historiesOf(run, gone.getId());
        assertEquals(1, skipped.size());
        assertTrue(skipped.get(0).getReason().contains("배치하는 사이 삭제됨"), skipped.get(0).getReason());
        assertEquals(Workload.WorkloadStatus.SCHEDULED, reload(kept).getStatus(), "다음 워크로드는 배치된다");
    }

    @DisplayName("재시도 간격이 지난 실패 워크로드는 겹침이 없으면 그대로 배치된다")
    @Test
    void retryable_failed_workload_is_placed_without_overlap() {
        Workload failed = new Workload(PREFIX + "ovl-retry", 1, 64);
        failed.setResourceType(Workload.ResourceType.NPU);
        failed.markFailed("앞선 실패");
        ReflectionTestUtils.setField(failed, "lastFailedAt", OffsetDateTime.now().minusMinutes(20));   // 재시도 간격이 지났다
        Workload saved = workloadRepository.saveAndFlush(failed);
        nodeThen(10, () -> { });

        ScheduleRun run = executionService.runNow(schedule(PREFIX + "s-ovl-retry"), "tester");

        assertEquals(Workload.WorkloadStatus.SCHEDULED, reload(saved).getStatus());
        assertEquals(1, run.getSuccessCount());
        assertEquals(1, dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(saved.getId()).size());
    }

    // ── 저장 실패와 예약 ──────────────────────────────────────────────────

    @DisplayName("배치 저장이 실패하면 그 건의 예약이 실행 후 스냅샷에 남지 않는다")
    @Test
    void failed_save_leaves_no_reservation_in_after_snapshot() {
        save(PREFIX + "save-fail", 5, Workload.WorkloadPriority.NORMAL);
        nodeThen(1, () -> { });   // CPU 1% — 5코어를 예약하면 가동 노드가 0 → 1 이 된다
        // 엔진은 성공하지만 사유가 이력 열(500자)보다 길어 이력 저장이 실패한다.
        doReturn(new SchedulingResult(true, "mdc-01", NODE, 0.9, "가".repeat(600), List.of()))
                .when(weighted).schedule(any(Workload.class), any(PlacementContext.class));

        long scheduleId = schedule(PREFIX + "s-save");
        assertThrows(BusinessException.class, () -> executionService.runNow(scheduleId, "tester"));

        ScheduleRun run = runRepository.findByScheduleId(scheduleId).get(0);
        assertEquals(ScheduleRun.RunStatus.FAILED, run.getStatus());
        assertEquals(run.getSnapshotActiveNodesBefore(), run.getSnapshotActiveNodesAfter(),
                "저장에 실패한 배치의 예약이 실행 후 스냅샷에 남으면 안 된다");
    }

    @DisplayName("전달 기록 마감 저장이 실패하면 커밋된 배치의 예약은 실행 후 스냅샷에 남는다")
    @Test
    void failed_dispatch_settle_keeps_reservation_in_after_snapshot() {
        Workload target = save(PREFIX + "settle-fail", 5, Workload.WorkloadPriority.NORMAL);
        nodeThen(1, () -> { });   // CPU 1% — 5코어를 예약하면 가동 노드가 0 → 1 이 된다
        // 실행 계층은 받지만 응답 메시지가 전달 기록 열(500자)보다 길어 전달 기록 마감 저장이 실패한다.
        // PlacementDispatch.accept 가 메시지를 자르지 않는 지금 동작에 기댄 실패 유발이다. 자르기를 더하면 유발 방법을 바꾼다.
        when(registry.current()).thenReturn(new PlacementDispatcher() {
            @Override public String getMode() { return "test"; }
            @Override public Outcome dispatch(Request request) { return Outcome.accepted(null, "가".repeat(600)); }
        });

        long scheduleId = schedule(PREFIX + "s-settle");
        assertThrows(BusinessException.class, () -> executionService.runNow(scheduleId, "tester"));

        ScheduleRun run = runRepository.findByScheduleId(scheduleId).get(0);
        assertEquals(ScheduleRun.RunStatus.FAILED, run.getStatus());
        assertEquals(Workload.WorkloadStatus.SCHEDULED, reload(target).getStatus(), "배치 결정은 전달 전에 이미 커밋됐다");
        List<PlacementDispatch> records = dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(target.getId());
        assertEquals(1, records.size());
        assertEquals(PlacementDispatch.DispatchStatus.REQUESTED, records.get(0).getStatus(), "마감 저장이 되돌아가 요청 상태로 남는다");
        assertEquals(Integer.valueOf(0), run.getSnapshotActiveNodesBefore());
        assertEquals(Integer.valueOf(1), run.getSnapshotActiveNodesAfter(),
                "커밋된 배치의 예약은 전달이 예외로 끝나도 되돌리지 않는다");
    }

    // ── 편집이 읽은 뒤 커밋된 다른 쓰기 ────────────────────────────────────

    @DisplayName("편집이 읽은 뒤 단건 배치가 커밋되면 편집은 409 이고 배치와 전달 기록은 그대로다")
    @Test
    void edit_after_single_placement_commit_is_rejected_and_keeps_placement() {
        Workload target = save(PREFIX + "ed-one", 1, Workload.WorkloadPriority.NORMAL);
        nodeThen(10, () -> { });
        Workload[] placed = new Workload[1];

        BusinessException e = editAfter(target.getId(), "{\"name\":\"" + PREFIX + "ed-one-b\"}", () -> {
            schedulingService.scheduleWorkload(target.getId(), null);
            placed[0] = workloadRepository.findById(target.getId()).orElseThrow();
        });

        assertNotNull(e, "편집이 막 끝난 배치를 편집 전 대기로 되돌리면 안 된다");
        assertEquals(HttpStatus.CONFLICT, e.getStatus(), e.getMessage());
        assertTrue(e.getMessage().startsWith("배치된 워크로드는 편집할 수 없습니다"), e.getMessage());
        Workload after = reload(target);
        assertEquals(Workload.WorkloadStatus.SCHEDULED, after.getStatus());
        assertEquals(placed[0].getAssignedNodeId(), after.getAssignedNodeId());
        assertEquals(placed[0].getScheduledAt().toInstant(), after.getScheduledAt().toInstant(), "배정 시각도 그대로");
        assertEquals(PREFIX + "ed-one", after.getName(), "막힌 편집은 반영되지 않는다");
        assertEquals(1, dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(target.getId()).size());
    }

    @DisplayName("편집이 읽은 뒤 스케줄 실행이 배치를 커밋하면 편집은 409 이고 배치는 그대로다")
    @Test
    void edit_after_scheduled_run_placement_commit_is_rejected_and_keeps_placement() {
        Workload target = save(PREFIX + "ed-run", 1, Workload.WorkloadPriority.NORMAL);
        nodeThen(10, () -> { });
        long scheduleId = schedule(PREFIX + "s-ed-run");   // 스케줄은 편집 트랜잭션 밖에서 미리 만든다

        BusinessException e = editAfter(target.getId(), "{\"requiredCpu\":2}",
                () -> executionService.runNow(scheduleId, "tester"));

        assertNotNull(e, "편집이 막 끝난 배치를 편집 전 대기로 되돌리면 안 된다");
        assertEquals(HttpStatus.CONFLICT, e.getStatus(), e.getMessage());
        assertTrue(e.getMessage().startsWith("배치된 워크로드는 편집할 수 없습니다"), e.getMessage());
        Workload after = reload(target);
        assertEquals(Workload.WorkloadStatus.SCHEDULED, after.getStatus());
        assertEquals(NODE, after.getAssignedNodeId());
        assertEquals(1, after.getRequiredCpu(), "막힌 편집은 반영되지 않는다");
        assertEquals(1, dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(target.getId()).size());
    }

    @DisplayName("편집이 읽은 뒤 커밋된 실패 결정은 편집이 되돌리지 않는다")
    @Test
    void edit_after_failure_decision_keeps_failure_and_retry_count() {
        Workload target = save(PREFIX + "ed-fail", 1, Workload.WorkloadPriority.NORMAL);

        BusinessException e = editAfter(target.getId(), "{\"name\":\"" + PREFIX + "ed-fail-b\"}", () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    Workload w = workloadRepository.findById(target.getId()).orElseThrow();
                    w.markFailed("겹친 실패 결정");
                    workloadRepository.saveAndFlush(w);
                }));

        assertNull(e, () -> "편집할 수 있는 상태 안의 변화는 편집을 막지 않는다: " + e);
        Workload after = reload(target);
        assertEquals(Workload.WorkloadStatus.FAILED, after.getStatus(), "실패 결정이 편집 전 대기로 돌아가면 안 된다");
        assertEquals(1, after.resolveRetryCount(), "재시도 횟수가 편집 전 값으로 돌아가면 안 된다");
        assertNotNull(after.getLastFailedAt());
        assertEquals(PREFIX + "ed-fail-b", after.getName(), "편집은 반영된다");
    }

    @DisplayName("편집이 읽은 뒤 커밋된 되살리기는 편집이 되돌리지 않는다")
    @Test
    void edit_after_revive_keeps_cleared_retry_count() {
        Workload failed = new Workload(PREFIX + "ed-revive", 1, 64);
        failed.setResourceType(Workload.ResourceType.NPU);
        failed.markFailed("앞선 실패 1");
        failed.markFailed("앞선 실패 2");
        Workload saved = workloadRepository.saveAndFlush(failed);

        BusinessException e = editAfter(saved.getId(), "{\"requiredCpu\":2}",
                () -> schedulingService.changeStatus(saved.getId(), Workload.WorkloadStatus.PENDING));

        assertNull(e, () -> "편집할 수 있는 상태 안의 변화는 편집을 막지 않는다: " + e);
        Workload after = reload(saved);
        assertEquals(Workload.WorkloadStatus.PENDING, after.getStatus(), "되살리기가 편집 전 실패로 돌아가면 안 된다");
        assertEquals(0, after.resolveRetryCount(), "되살리기가 지운 재시도 횟수가 돌아오면 안 된다");
        assertNull(after.getLastFailedAt());
        assertEquals(2, after.getRequiredCpu(), "편집은 반영된다");
    }

    @DisplayName("편집이 읽은 뒤 지워진 워크로드의 편집은 404 다")
    @Test
    void edit_after_delete_is_not_found() {
        Workload target = save(PREFIX + "ed-del", 1, Workload.WorkloadPriority.NORMAL);

        BusinessException e = editAfter(target.getId(), "{\"requiredCpu\":2}", () -> workloadService.delete(target.getId()));

        assertNotNull(e, "지워진 워크로드의 편집이 데이터베이스 오류로 끝나면 안 된다");
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus(), e.getMessage());
        assertTrue(workloadRepository.findById(target.getId()).isEmpty());
    }

    // ── 배치하는 사이 커밋된 실패 결정 · 되살리기와 재시도 기록 ───────────────────

    @DisplayName("배치하는 사이 다른 스케줄 실행이 실패를 기록해도 이 실행의 실패가 그 위에 쌓인다")
    @Test
    void overlapping_run_failure_is_counted_on_top_of_this_run_failure() {
        Workload target = save(PREFIX + "rc-run", 1, Workload.WorkloadPriority.NORMAL);
        nodeThen(10, () -> { });
        long other = schedule(PREFIX + "s-rc-other");
        failAllPlacementsAfter(() -> executionService.runNow(other, "tester"));

        ScheduleRun run = executionService.runNow(schedule(PREFIX + "s-rc-this"), "tester");

        Workload after = reload(target);
        assertEquals(Workload.WorkloadStatus.FAILED, after.getStatus());
        assertEquals(2, after.resolveRetryCount(), "겹친 두 실패가 모두 쌓여야 한다 — 한 번 덜 쌓이면 안 된다");
        assertNotNull(after.getLastFailedAt());
        List<SchedulingHistory> mine = historiesOf(run, target.getId());
        assertEquals(1, mine.size());
        assertFalse(mine.get(0).getSuccess());
        long others = historyRepository.findAll().stream()
                .filter(h -> target.getId().equals(h.getWorkloadId()) && !run.getId().equals(h.getScheduleRunId()))
                .count();
        assertEquals(1, others, "다른 실행도 실패 결정 한 건을 남긴다");
    }

    @DisplayName("배치하는 사이 단건 배치가 실패를 기록해도 이 실행의 실패가 그 위에 쌓인다")
    @Test
    void single_placement_failure_while_placing_is_counted() {
        Workload target = save(PREFIX + "rc-one", 1, Workload.WorkloadPriority.NORMAL);
        nodeThen(10, () -> { });
        failAllPlacementsAfter(() -> schedulingService.scheduleWorkload(target.getId(), null));

        executionService.runNow(schedule(PREFIX + "s-rc-one"), "tester");

        Workload after = reload(target);
        assertEquals(Workload.WorkloadStatus.FAILED, after.getStatus());
        assertEquals(2, after.resolveRetryCount(), "단건 배치의 실패 위에 이 실행의 실패가 쌓여야 한다");
    }

    @DisplayName("배치하는 사이 되살린 워크로드는 이 실행이 실패해도 재시도 횟수가 1 이다")
    @Test
    void revive_while_placing_keeps_cleared_retry_count_under_failure() {
        Workload failed = new Workload(PREFIX + "rc-revive", 1, 64);
        failed.setResourceType(Workload.ResourceType.NPU);
        failed.markFailed("앞선 실패 1");
        failed.markFailed("앞선 실패 2");
        OffsetDateTime old = OffsetDateTime.now().minusMinutes(20);   // 재시도 간격이 지났다
        ReflectionTestUtils.setField(failed, "lastFailedAt", old);
        Workload saved = workloadRepository.saveAndFlush(failed);
        nodeThen(10, () -> { });
        failAllPlacementsAfter(() -> schedulingService.changeStatus(saved.getId(), Workload.WorkloadStatus.PENDING));

        executionService.runNow(schedule(PREFIX + "s-rc-revive"), "tester");

        Workload after = reload(saved);
        assertEquals(Workload.WorkloadStatus.FAILED, after.getStatus());
        assertEquals(1, after.resolveRetryCount(), "되살리기가 지운 횟수 위에 이번 실패 한 번만 쌓인다");
        assertTrue(after.getLastFailedAt().isAfter(old.plusMinutes(10)), "마지막 실패 시각은 이번 실패 시각이다");
    }

    @DisplayName("배치하는 사이 커밋된 실패 결정의 재시도 횟수와 실패 시각은 이 실행의 배치 뒤에도 남는다")
    @Test
    void failure_committed_while_placing_survives_this_run_success() {
        Workload target = save(PREFIX + "rc-ok", 1, Workload.WorkloadPriority.NORMAL);
        nodeThen(10, () -> { });
        OffsetDateTime[] failedAt = new OffsetDateTime[1];
        elsewhereWhilePlacing(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Workload w = workloadRepository.findById(target.getId()).orElseThrow();
                w.markFailed("겹친 실패 결정");
                workloadRepository.saveAndFlush(w);
            });
            failedAt[0] = workloadRepository.findById(target.getId()).orElseThrow().getLastFailedAt();   // DB 에 저장된 값
        }, false);

        ScheduleRun run = executionService.runNow(schedule(PREFIX + "s-rc-ok"), "tester");

        Workload after = reload(target);
        assertEquals(Workload.WorkloadStatus.SCHEDULED, after.getStatus());
        assertEquals(NODE, after.getAssignedNodeId());
        assertEquals(1, after.resolveRetryCount(), "커밋된 실패 결정의 재시도 횟수가 배치 저장으로 되돌아가면 안 된다");
        assertNotNull(after.getLastFailedAt(), "커밋된 실패 시각이 배치 저장으로 지워지면 안 된다");
        assertEquals(failedAt[0].toInstant(), after.getLastFailedAt().toInstant());
        List<PlacementDispatch> dispatches = dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(target.getId());
        assertEquals(1, dispatches.size());
        assertEquals(NODE, dispatches.get(0).getNodeId());
        assertTrue(PlacementDispatchService.isCurrentDecision(dispatches.get(0), after), "전달 기록이 지금 결정의 기록이다");
        assertEquals(1, run.getSuccessCount());
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    /** 노드 수집 대역 — 첫 호출(대상 조회 뒤 · 배치 루프 앞)에서 한 번만 {@code between} 을 커밋한다. */
    private void nodeThen(double cpuPercent, Runnable between) {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        AtomicBoolean once = new AtomicBoolean(true);
        when(collector.collect()).thenAnswer(inv -> {
            if (once.getAndSet(false)) between.run();
            return new NodeResourceCollector.Snapshot(
                    List.of(new NodeResource("mdc-01", NODE, 8, 65536, 0, cpuPercent, 20, 0, 0, 500, 0, 0, 0)), List.of());
        });
    }

    /**
     * 엔진 스파이 — 첫 호출(배치 직전에 다시 읽은 뒤 · 저장 전)에서 {@code elsewhere} 를 별도 스레드로 끝까지 커밋한 뒤 이어 간다.
     * 이 회차의 배치 트랜잭션은 아직 워크로드 행을 잡지 않았으므로 다른 쪽이 막히지 않는다. {@code thenFail} 이면 이어 갈 때
     * 엔진 대신 실패 결정을 돌려준다.
     */
    private void elsewhereWhilePlacing(Runnable elsewhere, boolean thenFail) {
        AtomicBoolean once = new AtomicBoolean(true);
        doAnswer(inv -> {
            if (once.getAndSet(false)) {
                ExecutorService other = Executors.newSingleThreadExecutor();
                try {
                    other.submit(elsewhere).get(30, TimeUnit.SECONDS);
                } finally {
                    other.shutdownNow();
                }
                if (thenFail) return SchedulingResult.failure("자원 부족", "default");
            }
            return inv.callRealMethod();
        }).when(weighted).schedule(any(Workload.class), any(PlacementContext.class));
    }

    /**
     * 편집이 워크로드를 읽은 뒤 · 저장하기 전에 {@code elsewhere} 가 커밋하는 창 — 바깥 트랜잭션에서 워크로드를 먼저 읽어 두고
     * (편집이 같은 트랜잭션에서 그 사본을 받는다) {@code elsewhere} 를 별도 스레드로 끝까지 커밋한 뒤 같은 트랜잭션에서 편집한다.
     * 바깥 트랜잭션은 읽기만 했으므로 다른 쪽이 막히지 않는다. 편집이 {@link BusinessException} 을 내면 롤백하고 그 예외를
     * 돌려준다(없으면 null · 그 밖의 예외는 그대로 던진다).
     */
    private BusinessException editAfter(Long workloadId, String json, Runnable elsewhere) {
        BusinessException[] thrown = new BusinessException[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            workloadRepository.findById(workloadId).orElseThrow();
            ExecutorService other = Executors.newSingleThreadExecutor();
            try {
                other.submit(elsewhere).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("겹친 쓰기가 끝나지 않았다", e);
            } finally {
                other.shutdownNow();
            }
            try {
                workloadService.update(workloadId, body(json));
            } catch (BusinessException e) {
                thrown[0] = e;
                status.setRollbackOnly();
            }
        });
        return thrown[0];
    }

    /**
     * 엔진 스파이 — 모든 배치 판단을 실패로 돌려주고, 첫 호출(배치 직전에 다시 읽은 뒤 · 저장 전)에서 {@code elsewhere} 를 별도
     * 스레드로 끝까지 커밋한 뒤 이어 간다. {@code elsewhere} 안의 다른 실행 · 단건 배치도 같은 스파이를 지나 실패를 기록한다.
     */
    private void failAllPlacementsAfter(Runnable elsewhere) {
        AtomicBoolean once = new AtomicBoolean(true);
        doAnswer(inv -> {
            if (once.getAndSet(false)) {
                ExecutorService other = Executors.newSingleThreadExecutor();
                try {
                    other.submit(elsewhere).get(30, TimeUnit.SECONDS);
                } finally {
                    other.shutdownNow();
                }
            }
            return SchedulingResult.failure("자원 부족", "default");
        }).when(weighted).schedule(any(Workload.class), any(PlacementContext.class));
    }

    private Workload save(String name, int cpu, Workload.WorkloadPriority priority) {
        Workload w = new Workload(name, cpu, 64);
        w.setResourceType(Workload.ResourceType.NPU);
        w.setPriority(priority);
        return workloadRepository.saveAndFlush(w);
    }

    private long schedule(String id) {
        return scheduleService.create(new ScheduleRequest(id, id, Workload.ResourceType.NPU, "0 0 2 * * ?",
                false, true, "wcd", "weighted", null, null, null, null)).id();
    }

    private Workload reload(Workload w) {
        return workloadRepository.findById(w.getId()).orElseThrow();
    }

    private WorkloadUpdateRequest body(String json) {
        return objectMapper.readValue(json, WorkloadUpdateRequest.class);
    }

    private List<SchedulingHistory> historiesOf(ScheduleRun run, Long workloadId) {
        return historyRepository.findAll().stream()
                .filter(h -> run.getId().equals(h.getScheduleRunId()) && workloadId.equals(h.getWorkloadId()))
                .toList();
    }
}

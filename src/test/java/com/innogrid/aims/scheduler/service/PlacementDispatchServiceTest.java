package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dispatch.DispatcherRegistry;
import com.innogrid.aims.scheduler.dispatch.PlacementDispatcher;
import com.innogrid.aims.scheduler.dispatch.RecordingPlacementDispatcher;
import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.PlacementDispatch.DispatchStatus;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 결정 → 디스패치 → (거부·불가) 처리와 콜백 반영. 트랜잭션 테스트가 아니다 — 커밋 뒤 실제 상태를 본다.
 *
 * <p>레지스트리를 대역으로 바꿔 수락·거부·예외 디스패처를 끼운다. 워크로드는 배치 없이 {@code assignTo} 로 배정 상태를 만든다
 * — 여기서 보는 것은 결정 뒤의 처리이지 엔진이 아니다.</p>
 */
@SpringBootTest
class PlacementDispatchServiceTest {

    @Autowired PlacementDispatchService service;
    @Autowired SchedulingService schedulingService;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired WorkloadRepository workloadRepository;
    @MockitoBean DispatcherRegistry registry;
    @Autowired com.innogrid.aims.scheduler.config.SchedulerProperties properties;

    @AfterEach
    void clean() {
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith("dps-")).forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith("dps-")).forEach(workloadRepository::delete);
    }

    private Workload assigned(String name) {
        Workload w = new Workload(name, 2, 512);
        w.assignTo("mdc-01", "n1", 0.7, "ok");
        return workloadRepository.saveAndFlush(w);
    }

    private static SchedulingResult ok() {
        return new SchedulingResult(true, "mdc-01", "n1", 0.7, "ok", List.of());
    }

    private static PlacementDispatcher dispatcher(PlacementDispatcher.Outcome outcome) {
        return new PlacementDispatcher() {
            @Override public String getMode() { return "test"; }
            @Override public Outcome dispatch(Request request) { return outcome; }
        };
    }

    @DisplayName("성공 결정은 REQUESTED 행을 만들고 기록형 디스패처가 수락해 ACCEPTED 로 남는다 — 워크로드는 SCHEDULED 그대로")
    @Test
    void success_creates_accepted_dispatch() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-ok");

        PlacementDispatch d = service.afterPlacement(w, ok(), 42L);

        assertNotNull(d.getId());
        assertEquals(DispatchStatus.ACCEPTED, d.getStatus());
        assertEquals(42L, d.getScheduleRunId());
        assertEquals("SYSTEM", d.getCreatedBy(), "스케줄 실행에서 나온 결정은 SYSTEM");
        assertEquals("n1", d.getNodeId());
        assertNull(d.getExternalRef());
        assertEquals(Workload.WorkloadStatus.SCHEDULED, workloadRepository.findById(w.getId()).orElseThrow().getStatus());
        assertEquals(1, dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(w.getId()).size());
    }

    @DisplayName("같은 결정을 두 번 넘기면 같은 행을 돌려주고 새로 만들지 않는다 (멱등키)")
    @Test
    void same_decision_dispatched_once() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-dup");
        PlacementDispatch first = service.afterPlacement(w, ok(), null);
        PlacementDispatch second = service.afterPlacement(w, ok(), null);
        assertEquals(first.getId(), second.getId());
        assertEquals(1, dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(w.getId()).size());
    }

    @DisplayName("실패한 결정은 아무 행도 만들지 않는다")
    @Test
    void failed_decision_creates_nothing() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = workloadRepository.saveAndFlush(new Workload("dps-fail", 1, 64));
        assertNull(service.afterPlacement(w, SchedulingResult.failure("자원 부족"), null));
        assertTrue(dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(w.getId()).isEmpty());
    }

    @DisplayName("실행 계층이 거부하면 REJECTED 로 닫고 워크로드는 FAILED + 배정 해제 + 재시도 1")
    @Test
    void rejection_fails_workload_and_clears_assignment() {
        when(registry.current()).thenReturn(dispatcher(PlacementDispatcher.Outcome.rejected("노드 점검 중")));
        Workload w = assigned("dps-rej");

        PlacementDispatch d = service.afterPlacement(w, ok(), null);

        assertEquals(DispatchStatus.REJECTED, d.getStatus());
        assertEquals("노드 점검 중", d.getMessage());
        Workload after = workloadRepository.findById(w.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.FAILED, after.getStatus());
        assertNull(after.getAssignedNodeId());
        assertEquals(1, after.getRetryCount());
        assertTrue(after.getSchedulingReason().contains("거부"));
        assertEquals("n1", d.getNodeId(), "디스패치 기록에는 어느 노드로 보냈는지 남는다");
    }

    @DisplayName("디스패처가 예외를 던지면 UNAVAILABLE 로 기록하고 밖으로 던지지 않는다 — 워크로드는 FAILED")
    @Test
    void dispatcher_exception_recorded_as_unavailable() {
        when(registry.current()).thenReturn(new PlacementDispatcher() {
            @Override public String getMode() { return "boom"; }
            @Override public Outcome dispatch(Request request) { throw new IllegalStateException("연결 끊김"); }
        });
        Workload w = assigned("dps-boom");
        PlacementDispatch d = assertDoesNotThrow(() -> service.afterPlacement(w, ok(), null));
        assertEquals(DispatchStatus.UNAVAILABLE, d.getStatus());
        assertTrue(d.getMessage().contains("IllegalStateException"));
        assertEquals(Workload.WorkloadStatus.FAILED, workloadRepository.findById(w.getId()).orElseThrow().getStatus());
    }

    @DisplayName("콜백 — RUNNING→COMPLETED 는 워크로드도 따라가고, 같은 상태 재보고는 무변경, 끝난 뒤 FAILED 는 409")
    @Test
    void callback_transitions_follow_workload() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-cb");
        PlacementDispatch d = service.afterPlacement(w, ok(), null);

        service.applyResult(d.getId(), "running", "ext-9", "시작");
        assertEquals(Workload.WorkloadStatus.RUNNING, workloadRepository.findById(w.getId()).orElseThrow().getStatus());
        assertEquals("ext-9", service.require(d.getId()).getExternalRef());

        PlacementDispatch same = service.applyResult(d.getId(), "RUNNING", null, null);
        assertEquals(DispatchStatus.RUNNING, same.getStatus());

        service.applyResult(d.getId(), "COMPLETED", null, null);
        assertEquals(Workload.WorkloadStatus.COMPLETED, workloadRepository.findById(w.getId()).orElseThrow().getStatus());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.applyResult(d.getId(), "FAILED", null, "늦은 실패"));
        assertEquals(409, ex.getStatus().value());
    }

    @DisplayName("콜백 FAILED 는 워크로드를 FAILED 로 돌리고 사유를 남긴다. 모르는 상태·내부 상태·없는 id 는 400/404")
    @Test
    void callback_failed_and_validation() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-cbf");
        PlacementDispatch d = service.afterPlacement(w, ok(), null);

        assertEquals(400, assertThrows(BusinessException.class, () -> service.applyResult(d.getId(), "done", null, null)).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.applyResult(d.getId(), "REJECTED", null, null)).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.applyResult(d.getId(), " ", null, null)).getStatus().value());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.applyResult(999_999L, "RUNNING", null, null)).getStatus().value());

        service.applyResult(d.getId(), "failed", null, "이미지 없음");
        Workload after = workloadRepository.findById(w.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.FAILED, after.getStatus());
        assertTrue(after.getSchedulingReason().contains("이미지 없음"));
        assertNotNull(after.getAssignedNodeId(), "실행 중 실패는 어디서 실패했는지 배정을 남긴다");
    }

    @DisplayName("사유 없는 FAILED 콜백도 실패 횟수와 백오프 시각을 남긴다")
    @Test
    void callback_failed_without_message_still_records_retry() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-cbf-empty");
        PlacementDispatch d = service.afterPlacement(w, ok(), null);

        service.applyResult(d.getId(), "FAILED", null, null);

        Workload after = workloadRepository.findById(w.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.FAILED, after.getStatus());
        assertEquals(1, after.resolveRetryCount());
        assertNotNull(after.getLastFailedAt());
        assertEquals("실행 실패", after.getSchedulingReason());

        service.applyResult(d.getId(), "FAILED", null, null);
        assertEquals(1, workloadRepository.findById(w.getId()).orElseThrow().resolveRetryCount(),
                "같은 결과 재보고는 실패 횟수를 두 번 올리지 않는다");
    }

    @DisplayName("워크로드가 다른 경로로 먼저 바뀌어 전이가 안 되면 디스패치만 갱신하고 그 사실을 message 에 남긴다")
    @Test
    void callback_when_workload_already_moved() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-skew");
        PlacementDispatch d = service.afterPlacement(w, ok(), null);
        Workload moved = workloadRepository.findById(w.getId()).orElseThrow();
        moved.transitionTo(Workload.WorkloadStatus.RUNNING);
        moved.transitionTo(Workload.WorkloadStatus.COMPLETED);
        workloadRepository.saveAndFlush(moved);

        PlacementDispatch after = service.applyResult(d.getId(), "RUNNING", null, null);
        assertEquals(DispatchStatus.RUNNING, after.getStatus());
        assertTrue(after.getMessage().contains("COMPLETED"));
        assertEquals(Workload.WorkloadStatus.COMPLETED, workloadRepository.findById(w.getId()).orElseThrow().getStatus());
    }

    @DisplayName("워크로드별 조회는 최신순이고 삭제 정리는 그 워크로드의 행만 지운다")
    @Test
    void by_workload_and_delete() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload a = assigned("dps-a");
        Workload b = assigned("dps-b");
        service.afterPlacement(a, ok(), null);
        service.afterPlacement(b, ok(), null);
        assertEquals(1, service.byWorkload(a.getId()).size());
        assertEquals(1, service.deleteByWorkload(a.getId()));
        assertTrue(service.byWorkload(a.getId()).isEmpty());
        assertEquals(1, service.byWorkload(b.getId()).size());
    }

    @DisplayName("거부되면 호출자가 받는 배치 결과도 실패로 낮아진다 — 성공으로 세지 않게")
    @Test
    void rejection_downgrades_result_for_caller() {
        when(registry.current()).thenReturn(dispatcher(PlacementDispatcher.Outcome.rejected("점검 중")));
        Workload w = assigned("dps-down");
        SchedulingResult r = schedulingService.dispatch(w, ok(), null);
        assertFalse(r.success());
        assertTrue(r.reason().contains("n1") && r.reason().contains("점검 중"));
        assertTrue(schedulingService.dispatch(w, SchedulingResult.failure("x"), null).reason().equals("x"), "실패 결정은 그대로");
        SchedulingResult withPolicy = schedulingService.dispatch(w, ok().withPolicyName("vm-only"), null);
        assertFalse(withPolicy.success());
        assertEquals("vm-only", withPolicy.policyName(), "거부로 낮춘 결과에도 적용 정책 이름이 남는다");
    }

    @DisplayName("콜백의 externalRef 200자·message 500자 초과는 400 — DB 길이 위반 500 을 막는다")
    @Test
    void callback_length_limits_400() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-len");
        PlacementDispatch d = service.afterPlacement(w, ok(), null);
        assertEquals(400, assertThrows(BusinessException.class, () -> service.applyResult(d.getId(), "RUNNING", "x".repeat(201), null)).getStatus().value());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.applyResult(d.getId(), "RUNNING", null, "y".repeat(501))).getStatus().value());
        assertEquals(DispatchStatus.RUNNING, service.applyResult(d.getId(), "RUNNING", "x".repeat(200), "y".repeat(500)).getStatus());
    }

    // ── 상태 변경과 전달 기록 마감 ──────────────────────────────

    /**
     * 운영자가 상태 변경 API 로 워크로드를 끝 상태로 보내면 현재 결정의 전달 기록도 같은 끝 상태로 마감된다.
     * 전에는 워크로드만 바뀌고 기록은 ACCEPTED 로 그대로 남았다 — 기록형 디스패처 모드에서는 진행을 알리는 수단이
     * 콜백 API 와 이 API 둘뿐이라, 이 API 로 끝낸 워크로드의 기록이 열린 채 쌓였다.
     */
    @DisplayName("상태 변경으로 실패 처리하면 현재 결정의 전달 기록이 FAILED 로 마감되고 비고 앞에 표지가 붙는다")
    @Test
    void status_change_to_failed_closes_current_dispatch() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-close-failed");
        PlacementDispatch d = service.afterPlacement(w, ok(), null);
        assertEquals(DispatchStatus.ACCEPTED, d.getStatus());

        Workload after = schedulingService.changeStatus(w.getId(), Workload.WorkloadStatus.FAILED);

        assertEquals(Workload.WorkloadStatus.FAILED, after.getStatus());
        PlacementDispatch closed = dispatchRepository.findById(d.getId()).orElseThrow();
        assertEquals(DispatchStatus.FAILED, closed.getStatus());
        assertTrue(closed.getMessage().startsWith("운영자 상태 변경 — "), closed.getMessage());
        assertFalse(closed.getRespondedAt().isBefore(d.getRespondedAt()), "마감 시각이 수락 시각보다 앞설 수 없다");
    }

    @DisplayName("실행 중(RUNNING 기록) 워크로드를 상태 변경으로 완료하면 기록도 COMPLETED 로 마감되고, 늦게 온 FAILED 콜백은 409·COMPLETED 재보고는 무변경이다")
    @Test
    void status_change_to_completed_closes_running_dispatch_and_rejects_late_callback() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-close-completed");
        PlacementDispatch d = service.afterPlacement(w, ok(), null);

        schedulingService.changeStatus(w.getId(), Workload.WorkloadStatus.RUNNING);
        assertEquals(DispatchStatus.ACCEPTED, dispatchRepository.findById(d.getId()).orElseThrow().getStatus(),
                "실행 중으로 바꾸는 것은 끝 상태가 아니라 기록을 건드리지 않는다");
        service.applyResult(d.getId(), "RUNNING", null, null);
        assertEquals(DispatchStatus.RUNNING, dispatchRepository.findById(d.getId()).orElseThrow().getStatus());

        schedulingService.changeStatus(w.getId(), Workload.WorkloadStatus.COMPLETED);

        PlacementDispatch closed = dispatchRepository.findById(d.getId()).orElseThrow();
        assertEquals(DispatchStatus.COMPLETED, closed.getStatus());
        assertTrue(closed.getMessage().startsWith("운영자 상태 변경 — COMPLETED"), closed.getMessage());
        BusinessException e = assertThrows(BusinessException.class, () -> service.applyResult(d.getId(), "FAILED", null, null));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, e.getStatus());
        assertEquals(DispatchStatus.COMPLETED, service.applyResult(d.getId(), "COMPLETED", null, null).getStatus(),
                "같은 상태의 재보고는 무변경 200");
        assertEquals(Workload.WorkloadStatus.COMPLETED, workloadRepository.findById(w.getId()).orElseThrow().getStatus());
    }

    @DisplayName("전달 중(REQUESTED) 기록도 상태 변경으로 실패 처리하면 FAILED 로 마감된다")
    @Test
    void status_change_closes_requested_dispatch() {
        Workload w = assigned("dps-close-requested");
        PlacementDispatch requested = dispatchRepository.saveAndFlush(new PlacementDispatch(w, null, "tester"));
        assertEquals(DispatchStatus.REQUESTED, requested.getStatus());

        schedulingService.changeStatus(w.getId(), Workload.WorkloadStatus.FAILED);

        PlacementDispatch closed = dispatchRepository.findById(requested.getId()).orElseThrow();
        assertEquals(DispatchStatus.FAILED, closed.getStatus());
        assertTrue(closed.getMessage().startsWith("운영자 상태 변경 — FAILED"), closed.getMessage());
    }

    /**
     * 결함이 실제로 해를 끼치던 길. 상태 변경으로 실패 처리한 워크로드를 대기로 되살려 다시 배치하면, 전에는
     * 옛 결정의 기록이 ACCEPTED 로 남아 있어 그 기록에 늦게 온 RUNNING 콜백이 새 결정의 워크로드를 실행 중으로 옮겼다.
     */
    @DisplayName("실패 처리 뒤 되살려 다시 배치한 워크로드는 옛 결정의 기록에 온 늦은 콜백으로 옮겨지지 않는다")
    @Test
    void late_callback_on_old_dispatch_does_not_move_rescheduled_workload() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-close-revive");
        PlacementDispatch old = service.afterPlacement(w, ok(), null);

        schedulingService.changeStatus(w.getId(), Workload.WorkloadStatus.FAILED);
        schedulingService.changeStatus(w.getId(), Workload.WorkloadStatus.PENDING);
        Workload revived = workloadRepository.findById(w.getId()).orElseThrow();
        do {
            revived.assignTo("mdc-01", "n2", 0.6, "재배치");
        } while (PlacementDispatch.keyOf(revived).equals(old.getIdempotencyKey()));
        revived = workloadRepository.saveAndFlush(revived);
        PlacementDispatch current = service.afterPlacement(revived, ok(), null);
        assertNotEquals(old.getId(), current.getId());

        BusinessException e = assertThrows(BusinessException.class, () -> service.applyResult(old.getId(), "RUNNING", null, null));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, e.getStatus());
        assertEquals(Workload.WorkloadStatus.SCHEDULED, workloadRepository.findById(w.getId()).orElseThrow().getStatus(),
                "옛 기록의 늦은 콜백이 새 결정의 워크로드를 옮기면 안 된다");
        assertEquals(DispatchStatus.ACCEPTED, dispatchRepository.findById(current.getId()).orElseThrow().getStatus());
    }

    @DisplayName("상태 변경은 최신 결정의 기록만 마감하고 옛 결정의 기록은 건드리지 않는다")
    @Test
    void status_change_closes_only_latest_dispatch() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-close-latest");
        PlacementDispatch old = service.afterPlacement(w, ok(), null);
        do {
            w.assignTo("mdc-01", "n2", 0.6, "재배치");
        } while (PlacementDispatch.keyOf(w).equals(old.getIdempotencyKey()));
        w = workloadRepository.saveAndFlush(w);
        PlacementDispatch current = service.afterPlacement(w, ok(), null);
        assertNotEquals(old.getId(), current.getId());

        schedulingService.changeStatus(w.getId(), Workload.WorkloadStatus.FAILED);

        assertEquals(DispatchStatus.FAILED, dispatchRepository.findById(current.getId()).orElseThrow().getStatus());
        assertEquals(DispatchStatus.ACCEPTED, dispatchRepository.findById(old.getId()).orElseThrow().getStatus(), "옛 결정의 기록은 그대로");
    }

    @DisplayName("전달 기록이 없는 옛 배정은 상태만 바뀌고 기록은 생기지 않는다")
    @Test
    void status_change_without_dispatch_row_only_moves_workload() {
        Workload w = assigned("dps-close-none");

        Workload after = schedulingService.changeStatus(w.getId(), Workload.WorkloadStatus.FAILED);

        assertEquals(Workload.WorkloadStatus.FAILED, after.getStatus());
        assertTrue(dispatchRepository.findByWorkloadIdOrderByRequestedAtDescIdDesc(w.getId()).isEmpty());
    }

    // ── 예약 취소 ──────────────────────────────────────────────

    @DisplayName("예약 취소는 현재 결정의 ACCEPTED 기록만 FAILED 로 마감하고 옛 결정의 기록은 건드리지 않는다")
    @Test
    void release_closes_only_current_dispatch() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-rel-cur");
        PlacementDispatch old = service.afterPlacement(w, ok(), null);
        assertEquals(DispatchStatus.ACCEPTED, old.getStatus());
        // 같은 워크로드를 다시 배정 — 멱등키(배정 시각 ms)가 달라질 때까지 재배정한다
        do {
            w.assignTo("mdc-01", "n2", 0.6, "재배치");
        } while (PlacementDispatch.keyOf(w).equals(old.getIdempotencyKey()));
        w = workloadRepository.saveAndFlush(w);
        PlacementDispatch current = service.afterPlacement(w, ok(), null);
        assertNotEquals(old.getId(), current.getId());

        SchedulingService.Release r = schedulingService.release(w.getId());

        assertEquals(Workload.WorkloadStatus.PENDING, r.workload().getStatus());
        assertNull(r.workload().getAssignedNodeId());
        assertEquals("n2", r.releasedNodeId());
        assertEquals(current.getId(), r.dispatch().getId());
        assertEquals(DispatchStatus.FAILED, r.dispatch().getStatus());
        assertTrue(r.dispatch().getMessage().contains("예약 취소"));
        assertEquals(DispatchStatus.ACCEPTED, dispatchRepository.findById(old.getId()).orElseThrow().getStatus(), "옛 결정의 기록은 그대로");
    }

    @DisplayName("전달 중(REQUESTED)인 기록이 있으면 취소는 409 이고 워크로드는 SCHEDULED 로 남는다")
    @Test
    void release_conflicts_while_requested() {
        Workload w = assigned("dps-rel-req");
        dispatchRepository.saveAndFlush(new PlacementDispatch(w, null, "u"));   // REQUESTED 그대로

        BusinessException e = assertThrows(BusinessException.class, () -> schedulingService.release(w.getId()));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, e.getStatus());
        assertEquals(Workload.WorkloadStatus.SCHEDULED, workloadRepository.findById(w.getId()).orElseThrow().getStatus());
    }

    @DisplayName("전달 기록이 없는 배정(전달 기능 이전 데이터)도 취소되고 dispatch 는 null 이다")
    @Test
    void release_without_dispatch_record() {
        Workload w = assigned("dps-rel-none");

        SchedulingService.Release r = schedulingService.release(w.getId());

        assertNull(r.dispatch());
        assertEquals(Workload.WorkloadStatus.PENDING, r.workload().getStatus());
        assertEquals("n1", r.releasedNodeId());
        assertEquals("mdc-01", r.releasedMdcId());
    }

    @DisplayName("취소는 디스패처의 취소 훅을 한 번 부르고 훅이 예외를 던져도 취소를 끝낸다")
    @Test
    void release_calls_dispatcher_cancel_hook() {
        List<PlacementDispatcher.Request> cancelled = new java.util.ArrayList<>();
        PlacementDispatcher hooked = new PlacementDispatcher() {
            @Override public String getMode() { return "test"; }
            @Override public Outcome dispatch(Request request) { return Outcome.accepted("ext", "ok"); }
            @Override public void cancel(Request request) { cancelled.add(request); throw new IllegalStateException("원격 없음"); }
        };
        when(registry.current()).thenReturn(hooked);
        Workload w = assigned("dps-rel-hook");
        PlacementDispatch d = service.afterPlacement(w, ok(), null);

        SchedulingService.Release r = schedulingService.release(w.getId());

        assertEquals(1, cancelled.size());
        assertEquals(d.getId(), cancelled.get(0).dispatchId());
        assertEquals(w.getId(), cancelled.get(0).workloadId());
        assertEquals(0.7, cancelled.get(0).score(), "훅 요청은 취소 전의 결정 값(점수)으로 채운다");
        assertEquals("ok", cancelled.get(0).reason());
        assertEquals(DispatchStatus.FAILED, r.dispatch().getStatus(), "훅 예외에도 마감은 끝난다");
        assertTrue(r.dispatch().getMessage().contains("취소 훅 예외: IllegalStateException"), "훅 예외는 비고에 남는다");
    }

    @DisplayName("재시도 소진 워크로드를 단건 배치로 올린 뒤 취소하면 재시도 횟수가 남아 스케줄 실행 대상이 아니다")
    @Test
    void release_keeps_exhaustion_out_of_schedule_targets() {
        int max = properties.getMaxRetryCount();
        Workload w = new Workload("dps-rel-exh", 2, 512);
        for (int i = 0; i < max; i++) w.markFailed("자원 부족");
        w.assignTo("mdc-01", "n1", 0.7, "단건 배치");   // 단건 배치는 소진을 보지 않는다
        w = workloadRepository.saveAndFlush(w);

        SchedulingService.Release r = schedulingService.release(w.getId());

        assertEquals(Workload.WorkloadStatus.PENDING, r.workload().getStatus());
        assertEquals(max, r.workload().resolveRetryCount(), "취소는 재시도 회계를 지우지 않는다");
        List<Workload> targets = workloadRepository.findSchedulingCandidates(
                List.of(Workload.WorkloadStatus.PENDING, Workload.WorkloadStatus.FAILED), Workload.ResourceType.VM, true, max,
                java.time.OffsetDateTime.now().plusYears(1));
        assertTrue(targets.stream().noneMatch(x -> x.getId().equals(r.workload().getId())), "재시도 상한 조건에 걸려 대상에서 빠진다");
    }

    @DisplayName("취소된 워크로드는 다음 컨텍스트의 노드 예약에서 빠진다")
    @Test
    void release_frees_reservation_in_next_context() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-rel-resv");
        service.afterPlacement(w, ok(), null);
        assertEquals(Workload.WorkloadStatus.SCHEDULED, workloadRepository.findById(w.getId()).orElseThrow().getStatus());

        schedulingService.release(w.getId());

        assertTrue(workloadRepository.findByStatusIn(List.of(Workload.WorkloadStatus.SCHEDULED, Workload.WorkloadStatus.RUNNING))
                .stream().noneMatch(x -> x.getId().equals(w.getId())), "예약 장부(SCHEDULED·RUNNING 행)에서 빠진다");
    }

    // ── 옛 결정의 기록에 온 콜백 ─────────────────────────────────────────

    /** 옛 결정(ACCEPTED 기록)을 남긴 채 같은 워크로드를 다시 배정해 현재 결정의 기록을 만든다. 반환은 [옛 기록, 현재 기록]. */
    private PlacementDispatch[] oldAndCurrent(String name) {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned(name);
        PlacementDispatch old = service.afterPlacement(w, ok(), null);
        // 옛 기록의 요청 시각은 배정 시각보다 몇 ms 뒤에 찍히므로, 새 배정 시각이 그보다 확실히 뒤가 될 때까지 반복한다
        do {
            w.assignTo("mdc-01", "n2", 0.6, "재배치");
        } while (!w.getScheduledAt().isAfter(old.getRequestedAt().plusNanos(2_000_000)));
        w = workloadRepository.saveAndFlush(w);
        PlacementDispatch current = service.afterPlacement(w, ok(), null);
        assertNotEquals(old.getId(), current.getId());
        return new PlacementDispatch[] {old, current};
    }

    private Workload reload(PlacementDispatch d) {
        return workloadRepository.findById(d.getWorkloadId()).orElseThrow();
    }

    // ── 시작 시각(예약 반영의 기준) ─────────────────────────────────────

    @DisplayName("시작 시각은 현재 결정의 기록이 RUNNING 이 된 시각이다")
    @Test
    void running_since_is_callback_time_of_current_record() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-since");
        PlacementDispatch d = service.afterPlacement(w, ok(), null);
        // 수락 때의 응답 시각을 요청 3분 전으로 둔다 — RUNNING 콜백이 응답 시각을 새로 찍지 않는 실수를 시계 해상도와 무관하게 가른다
        PlacementDispatch accepted = dispatchRepository.findById(d.getId()).orElseThrow();
        ReflectionTestUtils.setField(accepted, "respondedAt", accepted.getRequestedAt().minusMinutes(3));
        dispatchRepository.saveAndFlush(accepted);
        service.applyResult(d.getId(), "RUNNING", "job-since", null);
        PlacementDispatch running = dispatchRepository.findById(d.getId()).orElseThrow();
        assertFalse(running.getRespondedAt().isBefore(running.getRequestedAt()), "RUNNING 콜백이 응답 시각을 콜백 시각으로 바꾼다");
        // 요청 시각과 확실히 다르게 둔다 — 응답 시각 대신 요청 시각을 돌려주는 실수를 가른다
        ReflectionTestUtils.setField(running, "respondedAt", running.getRequestedAt().plusMinutes(7));
        dispatchRepository.saveAndFlush(running);
        PlacementDispatch saved = dispatchRepository.findById(d.getId()).orElseThrow();
        assertEquals(DispatchStatus.RUNNING, saved.getStatus());

        Map<Long, OffsetDateTime> since = service.runningSince(List.of(reload(saved)));

        assertEquals(saved.getRespondedAt().toInstant(), since.get(w.getId()).toInstant());
        assertNotEquals(saved.getRequestedAt().toInstant(), since.get(w.getId()).toInstant());
    }

    @DisplayName("옛 결정의 RUNNING 기록은 시작 시각이 되지 않는다")
    @Test
    void running_since_ignores_old_decision_record() {
        PlacementDispatch[] d = oldAndCurrent("dps-since-old");
        PlacementDispatch old = d[0];
        service.applyResult(old.getId(), "RUNNING", "job-old", null);
        schedulingService.changeStatus(old.getWorkloadId(), Workload.WorkloadStatus.RUNNING);
        Workload running = reload(old);
        assertEquals(Workload.WorkloadStatus.RUNNING, running.getStatus());
        assertEquals(DispatchStatus.RUNNING, dispatchRepository.findById(old.getId()).orElseThrow().getStatus());

        assertTrue(service.runningSince(List.of(running)).isEmpty(), "옛 결정의 기록은 지금 실행의 시작을 말하지 않는다");
    }

    @DisplayName("운영자가 RUNNING 으로 바꾼 워크로드는 기록이 수락 상태라 시작 시각이 없다")
    @Test
    void running_since_ignores_accepted_record_of_operator_running() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-since-operator");
        PlacementDispatch d = service.afterPlacement(w, ok(), null);
        schedulingService.changeStatus(w.getId(), Workload.WorkloadStatus.RUNNING);
        Workload running = workloadRepository.findById(w.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.RUNNING, running.getStatus());
        assertEquals(DispatchStatus.ACCEPTED, dispatchRepository.findById(d.getId()).orElseThrow().getStatus());

        assertTrue(service.runningSince(List.of(running)).isEmpty(), "상태 변경은 기록을 건드리지 않는다 — 배치 시각으로 대신한다");
    }

    @DisplayName("옛 결정의 열린 기록에 온 RUNNING·COMPLETED 콜백은 기록만 바꾸고 표지를 남기며 워크로드는 SCHEDULED 그대로다")
    @Test
    void late_running_callback_on_old_open_dispatch_updates_record_only() {
        PlacementDispatch[] d = oldAndCurrent("dps-old-running");
        PlacementDispatch old = d[0], current = d[1];

        PlacementDispatch r = service.applyResult(old.getId(), "RUNNING", "job-old", null);
        assertEquals(DispatchStatus.RUNNING, r.getStatus(), "실행 계층이 말한 사실은 기록에 남는다");
        assertTrue(r.getMessage().contains(PlacementDispatchService.OLD_DECISION_NOTE), r.getMessage());
        Workload w = reload(old);
        assertEquals(Workload.WorkloadStatus.SCHEDULED, w.getStatus(), "옛 결정의 콜백은 워크로드를 옮기지 않는다");
        assertEquals("n2", w.getAssignedNodeId(), "현재 결정의 배정 그대로");
        assertEquals(0, w.resolveRetryCount());
        assertEquals(DispatchStatus.ACCEPTED, dispatchRepository.findById(current.getId()).orElseThrow().getStatus());

        assertEquals(DispatchStatus.RUNNING, service.applyResult(old.getId(), "RUNNING", null, null).getStatus(), "같은 상태 재보고는 무변경");
        assertEquals(DispatchStatus.COMPLETED, service.applyResult(old.getId(), "COMPLETED", null, null).getStatus());
        assertEquals(Workload.WorkloadStatus.SCHEDULED, reload(old).getStatus(), "옛 기록이 끝나도 워크로드는 그대로");
    }

    @DisplayName("옛 결정의 열린 기록에 온 FAILED 콜백은 워크로드의 재시도 회계와 배정을 건드리지 않고, 현재 결정의 콜백은 여전히 워크로드를 옮긴다")
    @Test
    void late_failed_callback_on_old_open_dispatch_keeps_retry_accounting() {
        PlacementDispatch[] d = oldAndCurrent("dps-old-failed");
        PlacementDispatch old = d[0], current = d[1];

        PlacementDispatch r = service.applyResult(old.getId(), "FAILED", null, "이미지 없음");
        assertEquals(DispatchStatus.FAILED, r.getStatus());
        assertEquals("이미지 없음 / " + PlacementDispatchService.OLD_DECISION_NOTE, r.getMessage(), "사유 뒤에 표지");
        Workload w = reload(old);
        assertEquals(Workload.WorkloadStatus.SCHEDULED, w.getStatus());
        assertEquals(0, w.resolveRetryCount(), "옛 결정의 실패는 재시도 횟수를 올리지 않는다");
        assertNull(w.getLastFailedAt());
        assertEquals("재배치", w.getSchedulingReason());
        assertEquals("n2", w.getAssignedNodeId());

        service.applyResult(current.getId(), "RUNNING", null, null);
        assertEquals(Workload.WorkloadStatus.RUNNING, reload(current).getStatus(), "현재 결정의 콜백은 전처럼 워크로드를 옮긴다");
    }

    @DisplayName("실행 중 워크로드에 옛 결정의 기록으로 온 COMPLETED 콜백은 워크로드를 완료로 옮기지 않고 예약 장부에도 남긴다")
    @Test
    void late_completed_callback_on_old_dispatch_keeps_running_workload() {
        PlacementDispatch[] d = oldAndCurrent("dps-old-completed");
        PlacementDispatch old = d[0], current = d[1];
        service.applyResult(current.getId(), "RUNNING", null, null);
        assertEquals(Workload.WorkloadStatus.RUNNING, reload(current).getStatus());

        service.applyResult(old.getId(), "RUNNING", null, null);
        assertEquals(Workload.WorkloadStatus.RUNNING, reload(old).getStatus());
        PlacementDispatch r = service.applyResult(old.getId(), "COMPLETED", null, null);
        assertEquals(DispatchStatus.COMPLETED, r.getStatus());
        Workload w = reload(old);
        assertEquals(Workload.WorkloadStatus.RUNNING, w.getStatus(), "옛 결정의 완료 콜백이 실행 중 워크로드를 끝내면 안 된다");
        assertEquals(0, w.resolveRetryCount());
        assertTrue(workloadRepository.findByStatusIn(List.of(Workload.WorkloadStatus.SCHEDULED, Workload.WorkloadStatus.RUNNING))
                .stream().anyMatch(x -> x.getId().equals(w.getId())), "예약 장부에 남는다");
        assertEquals(DispatchStatus.RUNNING, dispatchRepository.findById(current.getId()).orElseThrow().getStatus());
    }

    @DisplayName("엔진 실패로 실패한 뒤 되살려 다시 배치한 워크로드는 옛 결정의 열린 기록에 온 콜백으로 옮겨지지 않는다")
    @Test
    void revived_after_engine_failure_ignores_callback_on_old_open_dispatch() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-old-revive");
        PlacementDispatch old = service.afterPlacement(w, ok(), null);
        w = workloadRepository.findById(w.getId()).orElseThrow();
        w.markFailed("엔진 실패");                       // 단건 배치의 엔진 실패 — 배정 시각은 그대로, 기록은 열린 채
        workloadRepository.saveAndFlush(w);
        schedulingService.changeStatus(w.getId(), Workload.WorkloadStatus.PENDING);
        assertEquals(DispatchStatus.ACCEPTED, dispatchRepository.findById(old.getId()).orElseThrow().getStatus(), "되살리기는 기록을 닫지 않는다");
        w = workloadRepository.findById(w.getId()).orElseThrow();
        do {
            w.assignTo("mdc-01", "n2", 0.6, "재배치");
        } while (!w.getScheduledAt().isAfter(old.getRequestedAt().plusNanos(2_000_000)));
        w = workloadRepository.saveAndFlush(w);
        PlacementDispatch current = service.afterPlacement(w, ok(), null);

        PlacementDispatch r = service.applyResult(old.getId(), "RUNNING", null, null);
        assertEquals(DispatchStatus.RUNNING, r.getStatus());
        assertTrue(r.getMessage().contains(PlacementDispatchService.OLD_DECISION_NOTE));
        assertEquals(Workload.WorkloadStatus.SCHEDULED, reload(old).getStatus());
        assertEquals(DispatchStatus.ACCEPTED, dispatchRepository.findById(current.getId()).orElseThrow().getStatus());
    }
}

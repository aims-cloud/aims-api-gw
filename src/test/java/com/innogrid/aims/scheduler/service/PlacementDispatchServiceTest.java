package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
        assertEquals(1, dispatchRepository.findByWorkloadIdOrderByRequestedAtDesc(w.getId()).size());
    }

    @DisplayName("같은 결정을 두 번 넘기면 같은 행을 돌려주고 새로 만들지 않는다 (멱등키)")
    @Test
    void same_decision_dispatched_once() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = assigned("dps-dup");
        PlacementDispatch first = service.afterPlacement(w, ok(), null);
        PlacementDispatch second = service.afterPlacement(w, ok(), null);
        assertEquals(first.getId(), second.getId());
        assertEquals(1, dispatchRepository.findByWorkloadIdOrderByRequestedAtDesc(w.getId()).size());
    }

    @DisplayName("실패한 결정은 아무 행도 만들지 않는다")
    @Test
    void failed_decision_creates_nothing() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
        Workload w = workloadRepository.saveAndFlush(new Workload("dps-fail", 1, 64));
        assertNull(service.afterPlacement(w, SchedulingResult.failure("자원 부족"), null));
        assertTrue(dispatchRepository.findByWorkloadIdOrderByRequestedAtDesc(w.getId()).isEmpty());
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

    @DisplayName("운영자가 상태 변경 API 로 먼저 바꿔 워크로드 전이가 안 되면 디스패치만 갱신하고 그 사실을 message 에 남긴다")
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
}

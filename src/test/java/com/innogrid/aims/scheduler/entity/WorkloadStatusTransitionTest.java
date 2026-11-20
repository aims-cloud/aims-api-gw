package com.innogrid.aims.scheduler.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.innogrid.aims.scheduler.entity.Workload.ResourceType;
import com.innogrid.aims.scheduler.entity.Workload.WorkloadStatus;

import static org.junit.jupiter.api.Assertions.*;

class WorkloadStatusTransitionTest {

    @DisplayName("배치된 워크로드는 실행으로 넘어간다")
    @Test
    void scheduled_to_running() {
        Workload workload = scheduled();

        assertTrue(workload.transitionTo(WorkloadStatus.RUNNING));
        assertEquals(WorkloadStatus.RUNNING, workload.getStatus());
    }

    @DisplayName("실행 중인 워크로드는 완료 또는 실패로 끝난다")
    @Test
    void running_ends_completed_or_failed() {
        Workload done = scheduled();
        done.transitionTo(WorkloadStatus.RUNNING);
        assertTrue(done.transitionTo(WorkloadStatus.COMPLETED));
        assertEquals(WorkloadStatus.COMPLETED, done.getStatus());

        Workload failed = scheduled();
        failed.transitionTo(WorkloadStatus.RUNNING);
        assertTrue(failed.transitionTo(WorkloadStatus.FAILED));
        assertEquals(WorkloadStatus.FAILED, failed.getStatus());
    }

    @DisplayName("배치 전 워크로드는 실행으로 넘어갈 수 없다")
    @Test
    void pending_cannot_go_running() {
        Workload pending = new Workload("job", 1, 1024);

        assertEquals(WorkloadStatus.PENDING, pending.getStatus());
        assertFalse(pending.transitionTo(WorkloadStatus.RUNNING));
        assertEquals(WorkloadStatus.PENDING, pending.getStatus(), "실패한 전이는 상태를 바꾸지 않는다");
    }

    @DisplayName("단계를 건너뛰거나 되돌리는 전이는 거부한다")
    @Test
    void skip_or_reverse_rejected() {
        Workload workload = scheduled();

        assertFalse(workload.transitionTo(WorkloadStatus.COMPLETED), "SCHEDULED 에서 바로 완료 불가");
        assertFalse(workload.transitionTo(WorkloadStatus.PENDING), "되돌리기 불가");
        assertFalse(workload.transitionTo(WorkloadStatus.SCHEDULED), "같은 상태로 재전이 불가");
        assertEquals(WorkloadStatus.SCHEDULED, workload.getStatus());
    }

    @DisplayName("종료된 워크로드는 더 이상 전이하지 않는다")
    @Test
    void terminal_has_no_transition() {
        Workload workload = scheduled();
        workload.transitionTo(WorkloadStatus.RUNNING);
        workload.transitionTo(WorkloadStatus.COMPLETED);

        assertFalse(workload.transitionTo(WorkloadStatus.RUNNING));
        assertFalse(workload.transitionTo(WorkloadStatus.FAILED));
    }

    @DisplayName("null 전이는 거부한다")
    @Test
    void null_transition_rejected() {
        assertFalse(scheduled().transitionTo(null));
    }

    @DisplayName("리소스 종류가 없으면 VM 으로 해석한다")
    @Test
    void missing_resource_type_is_vm() {
        Workload workload = new Workload("job", 1, 1024);

        assertNull(workload.getResourceType());
        assertEquals(ResourceType.VM, workload.resolveResourceType());

        workload.setResourceType(ResourceType.GPU);
        assertEquals(ResourceType.GPU, workload.resolveResourceType());
    }

    // ── 재시도 ─────────────────────────────────────────────────

    @DisplayName("배치에 실패하면 재시도 횟수가 쌓인다")
    @Test
    void failed_placement_increments_retry() {
        Workload workload = new Workload("job", 1, 1024);
        assertEquals(0, workload.resolveRetryCount(), "값이 없으면 0으로 본다");

        workload.markFailed("자원 부족");
        workload.markFailed("자원 부족");

        assertEquals(2, workload.resolveRetryCount());
    }

    @DisplayName("배치에 성공해도 실행이 끝나기 전에는 재시도 횟수를 유지한다")
    @Test
    void successful_placement_keeps_retry_until_completion() {
        Workload workload = new Workload("job", 1, 1024);
        workload.markFailed("자원 부족");

        workload.assignTo("mdc-01", "node-1", 0.9, "배치됨");

        assertEquals(1, workload.resolveRetryCount(), "배치 판단만 성공하고 실행이 실패할 수 있다");

        workload.transitionTo(WorkloadStatus.RUNNING);
        workload.transitionTo(WorkloadStatus.COMPLETED);
        assertEquals(0, workload.resolveRetryCount(), "실제 실행 완료에서만 실패 누적을 지운다");
        assertNull(workload.getLastFailedAt());
    }

    @DisplayName("배치 성공과 실행 실패를 반복해도 재시도 횟수가 다섯 번까지 누적된다")
    @Test
    void repeated_dispatch_failures_reach_retry_limit() {
        Workload workload = new Workload("job", 1, 1024);

        for (int attempt = 1; attempt <= 5; attempt++) {
            workload.assignTo("mdc-01", "node-1", 0.9, "배치됨");
            workload.failDispatch("실행 실패");
            assertEquals(attempt, workload.resolveRetryCount());
        }
    }

    @DisplayName("실패한 워크로드는 대기로 되돌릴 수 있다")
    @Test
    void failed_can_reset_to_pending() {
        // 재시도 상한에 걸린 워크로드를 살릴 유일한 경로다
        Workload workload = scheduled();
        workload.transitionTo(WorkloadStatus.RUNNING);
        workload.transitionTo(WorkloadStatus.FAILED);

        assertTrue(workload.transitionTo(WorkloadStatus.PENDING));
        assertEquals(WorkloadStatus.PENDING, workload.getStatus());
    }

    @DisplayName("대기로 되돌리면 재시도 횟수도 지워진다")
    @Test
    void reset_to_pending_clears_retry() {
        // 지우지 않으면 되살려도 곧바로 상한에 걸려 다시 대상에서 빠진다
        Workload workload = new Workload("job", 1, 1024);
        workload.markFailed("자원 부족");
        workload.markFailed("자원 부족");

        assertTrue(workload.transitionTo(WorkloadStatus.PENDING));
        assertEquals(0, workload.resolveRetryCount());
    }

    @DisplayName("실패에서 다른 상태로는 넘어갈 수 없다")
    @Test
    void failed_cannot_transition_out() {
        Workload workload = new Workload("job", 1, 1024);
        workload.markFailed("자원 부족");

        assertFalse(workload.transitionTo(WorkloadStatus.RUNNING));
        assertFalse(workload.transitionTo(WorkloadStatus.SCHEDULED));
        assertFalse(workload.transitionTo(WorkloadStatus.COMPLETED));
    }

    @DisplayName("리소스 종류는 네 가지다")
    @Test
    void resource_type_has_four_values() {
        assertArrayEquals(
                new ResourceType[]{ResourceType.VM, ResourceType.GPU, ResourceType.NPU, ResourceType.CONTAINER},
                ResourceType.values());
    }

    private Workload scheduled() {
        Workload workload = new Workload("job", 1, 1024);
        workload.assignTo("mdc-01", "node-1", 0.9, "배치됨");
        return workload;
    }

    // ── 예약 취소(별도 진입점) ──────────────────────────────────

    @DisplayName("배치된 워크로드의 예약을 취소하면 대기로 돌아가고 배정은 풀리지만 재시도 회계와 배정 시각은 그대로다")
    @Test
    void release_returns_to_pending_and_clears_assignment() {
        Workload workload = new Workload("job", 1, 1024);
        workload.markFailed("한 번 실패");
        workload.assignTo("mdc-01", "node-1", 0.9, "배치됨");
        var scheduledAt = workload.getScheduledAt();

        assertTrue(workload.release("예약 취소 — 이전 배정 mdc-01/node-1"));

        assertEquals(WorkloadStatus.PENDING, workload.getStatus());
        assertNull(workload.getAssignedMdcId());
        assertNull(workload.getAssignedNodeId());
        assertNull(workload.getSchedulingScore());
        assertEquals(1, workload.resolveRetryCount(), "실패가 아니라 재시도 횟수 불변");
        assertNotNull(workload.getLastFailedAt(), "마지막 실패 시각 불변");
        assertEquals(scheduledAt, workload.getScheduledAt(), "추적용 배정 시각은 남긴다");
        assertTrue(workload.getSchedulingReason().startsWith("예약 취소"));
    }

    @DisplayName("배치되지 않은 상태에서는 예약 취소가 아무것도 바꾸지 않는다")
    @ParameterizedTest
    @EnumSource(value = WorkloadStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "SCHEDULED")
    void release_only_from_scheduled(WorkloadStatus start) {
        Workload workload = inState(start);
        assertEquals(start, workload.getStatus());
        String nodeBefore = workload.getAssignedNodeId();

        assertFalse(workload.release("x"), start + " 에서는 false");
        assertEquals(start, workload.getStatus());
        assertEquals(nodeBefore, workload.getAssignedNodeId(), "배정도 그대로");
    }

    /** 각 상태에 놓인 워크로드 — PENDING은 새 객체, 나머지는 배정 뒤 전이로 만든다. */
    private Workload inState(WorkloadStatus target) {
        if (target == WorkloadStatus.PENDING) return new Workload("job", 1, 1024);
        Workload workload = scheduled();
        switch (target) {
            case FAILED -> workload.markFailed("x");
            case RUNNING -> workload.transitionTo(WorkloadStatus.RUNNING);
            case COMPLETED -> { workload.transitionTo(WorkloadStatus.RUNNING); workload.transitionTo(WorkloadStatus.COMPLETED); }
            default -> { }
        }
        return workload;
    }

    @DisplayName("전이표에는 SCHEDULED→PENDING 이 여전히 없다 — 되돌리기는 별도 진입점만")
    @Test
    void transition_table_still_rejects_scheduled_to_pending() {
        assertFalse(WorkloadStatus.SCHEDULED.canTransitionTo(WorkloadStatus.PENDING));
        Workload workload = scheduled();
        assertFalse(workload.transitionTo(WorkloadStatus.PENDING));
        assertEquals(WorkloadStatus.SCHEDULED, workload.getStatus());
    }

    @DisplayName("편집할 수 있는 상태는 대기와 실패뿐이다")
    @Test
    void editable_only_in_pending_and_failed() {
        assertTrue(WorkloadStatus.PENDING.isEditable());
        assertTrue(WorkloadStatus.FAILED.isEditable());
        assertFalse(WorkloadStatus.SCHEDULED.isEditable(), "예약 장부 행");
        assertFalse(WorkloadStatus.RUNNING.isEditable(), "예약 장부 행");
        assertFalse(WorkloadStatus.COMPLETED.isEditable(), "실행 기록");
    }
}

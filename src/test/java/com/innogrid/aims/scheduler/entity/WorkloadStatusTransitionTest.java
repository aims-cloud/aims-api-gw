package com.innogrid.aims.scheduler.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    @DisplayName("배치에 성공하면 재시도 횟수가 지워진다")
    @Test
    void successful_placement_clears_retry() {
        Workload workload = new Workload("job", 1, 1024);
        workload.markFailed("자원 부족");

        workload.assignTo("mdc-01", "node-1", 0.9, "배치됨");

        assertEquals(0, workload.resolveRetryCount());
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
}

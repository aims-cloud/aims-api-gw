package com.innogrid.aims.scheduler.entity;

import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.Workload.ResourceType;
import com.innogrid.aims.scheduler.entity.Workload.WorkloadStatus;

import static org.junit.jupiter.api.Assertions.*;

class WorkloadStatusTransitionTest {

    @Test
    void 배치된_워크로드는_실행으로_넘어간다() {
        Workload workload = scheduled();

        assertTrue(workload.transitionTo(WorkloadStatus.RUNNING));
        assertEquals(WorkloadStatus.RUNNING, workload.getStatus());
    }

    @Test
    void 실행_중인_워크로드는_완료_또는_실패로_끝난다() {
        Workload done = scheduled();
        done.transitionTo(WorkloadStatus.RUNNING);
        assertTrue(done.transitionTo(WorkloadStatus.COMPLETED));
        assertEquals(WorkloadStatus.COMPLETED, done.getStatus());

        Workload failed = scheduled();
        failed.transitionTo(WorkloadStatus.RUNNING);
        assertTrue(failed.transitionTo(WorkloadStatus.FAILED));
        assertEquals(WorkloadStatus.FAILED, failed.getStatus());
    }

    @Test
    void 배치_전_워크로드는_실행으로_넘어갈_수_없다() {
        Workload pending = new Workload("job", 1, 1024);

        assertEquals(WorkloadStatus.PENDING, pending.getStatus());
        assertFalse(pending.transitionTo(WorkloadStatus.RUNNING));
        assertEquals(WorkloadStatus.PENDING, pending.getStatus(), "실패한 전이는 상태를 바꾸지 않는다");
    }

    @Test
    void 단계를_건너뛰거나_되돌리는_전이는_거부한다() {
        Workload workload = scheduled();

        assertFalse(workload.transitionTo(WorkloadStatus.COMPLETED), "SCHEDULED 에서 바로 완료 불가");
        assertFalse(workload.transitionTo(WorkloadStatus.PENDING), "되돌리기 불가");
        assertFalse(workload.transitionTo(WorkloadStatus.SCHEDULED), "같은 상태로 재전이 불가");
        assertEquals(WorkloadStatus.SCHEDULED, workload.getStatus());
    }

    @Test
    void 종료된_워크로드는_더_이상_전이하지_않는다() {
        Workload workload = scheduled();
        workload.transitionTo(WorkloadStatus.RUNNING);
        workload.transitionTo(WorkloadStatus.COMPLETED);

        assertFalse(workload.transitionTo(WorkloadStatus.RUNNING));
        assertFalse(workload.transitionTo(WorkloadStatus.FAILED));
    }

    @Test
    void null_전이는_거부한다() {
        assertFalse(scheduled().transitionTo(null));
    }

    @Test
    void 리소스_종류가_없으면_VM_으로_해석한다() {
        Workload workload = new Workload("job", 1, 1024);

        assertNull(workload.getResourceType());
        assertEquals(ResourceType.VM, workload.resolveResourceType());

        workload.setResourceType(ResourceType.GPU);
        assertEquals(ResourceType.GPU, workload.resolveResourceType());
    }

    // ── 재시도 ─────────────────────────────────────────────────

    @Test
    void 배치에_실패하면_재시도_횟수가_쌓인다() {
        Workload workload = new Workload("job", 1, 1024);
        assertEquals(0, workload.resolveRetryCount(), "값이 없으면 0으로 본다");

        workload.markFailed("자원 부족");
        workload.markFailed("자원 부족");

        assertEquals(2, workload.resolveRetryCount());
    }

    @Test
    void 배치에_성공하면_재시도_횟수가_지워진다() {
        Workload workload = new Workload("job", 1, 1024);
        workload.markFailed("자원 부족");

        workload.assignTo("mdc-01", "node-1", 0.9, "배치됨");

        assertEquals(0, workload.resolveRetryCount());
    }

    @Test
    void 실패한_워크로드는_대기로_되돌릴_수_있다() {
        // 재시도 상한에 걸린 워크로드를 살릴 유일한 경로다
        Workload workload = scheduled();
        workload.transitionTo(WorkloadStatus.RUNNING);
        workload.transitionTo(WorkloadStatus.FAILED);

        assertTrue(workload.transitionTo(WorkloadStatus.PENDING));
        assertEquals(WorkloadStatus.PENDING, workload.getStatus());
    }

    @Test
    void 대기로_되돌리면_재시도_횟수도_지워진다() {
        // 지우지 않으면 되살려도 곧바로 상한에 걸려 다시 대상에서 빠진다
        Workload workload = new Workload("job", 1, 1024);
        workload.markFailed("자원 부족");
        workload.markFailed("자원 부족");

        assertTrue(workload.transitionTo(WorkloadStatus.PENDING));
        assertEquals(0, workload.resolveRetryCount());
    }

    @Test
    void 실패에서_다른_상태로는_넘어갈_수_없다() {
        Workload workload = new Workload("job", 1, 1024);
        workload.markFailed("자원 부족");

        assertFalse(workload.transitionTo(WorkloadStatus.RUNNING));
        assertFalse(workload.transitionTo(WorkloadStatus.SCHEDULED));
        assertFalse(workload.transitionTo(WorkloadStatus.COMPLETED));
    }

    @Test
    void 리소스_종류는_네_가지다() {
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

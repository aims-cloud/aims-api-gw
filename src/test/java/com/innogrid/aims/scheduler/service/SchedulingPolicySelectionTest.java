package com.innogrid.aims.scheduler.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.entity.Workload.ResourceType;
import com.innogrid.aims.scheduler.entity.Workload.WorkloadStatus;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class SchedulingPolicySelectionTest {

    @Autowired private SchedulingService schedulingService;
    @Autowired private SchedulingPolicyRepository policyRepository;
    @Autowired private WorkloadRepository workloadRepository;

    @BeforeEach
    void clean() {
        policyRepository.deleteAll();
        workloadRepository.deleteAll();
    }

    // ── 정책 선택 ───────────────────────────────────────────────

    @DisplayName("종류별 활성 정책이 공통보다 우선한다")
    @Test
    void type_active_beats_common() {
        policyRepository.save(active("common", null));
        policyRepository.save(active("gpu", ResourceType.GPU));

        assertEquals("gpu", schedulingService.resolvePolicy(ResourceType.GPU).getName());
        assertEquals("common", schedulingService.resolvePolicy(ResourceType.VM).getName(),
                "종류별 정책이 없으면 공통으로 내려온다");
    }

    @DisplayName("종류를 지정하지 않으면 공통 정책을 쓴다")
    @Test
    void no_type_uses_common_policy() {
        policyRepository.save(active("common", null));
        policyRepository.save(active("gpu", ResourceType.GPU));

        assertEquals("common", schedulingService.resolvePolicy().getName());
    }

    @DisplayName("활성 정책이 없으면 기본 정책을 만든다")
    @Test
    void no_active_policy_creates_default() {
        assertEquals(0, policyRepository.count());

        SchedulingPolicy created = schedulingService.resolvePolicy(ResourceType.NPU);

        assertEquals("default", created.getName());
        assertEquals(1, policyRepository.count(), "기본 정책이 저장된다");
    }

    @DisplayName("활성 정책이 둘이어도 예외가 나지 않는다")
    @Test
    void two_active_policies_no_exception() {
        policyRepository.save(active("common", null));
        policyRepository.save(active("gpu", ResourceType.GPU));

        assertDoesNotThrow(() -> schedulingService.resolvePolicy(ResourceType.GPU));
        assertDoesNotThrow(() -> schedulingService.resolvePolicy(ResourceType.CONTAINER));
    }

    // ── 상태 전이 ───────────────────────────────────────────────

    @DisplayName("상태 전이가 저장된다")
    @Test
    void status_transition_saved() {
        Workload workload = workloadRepository.save(scheduledWorkload());

        Workload running = schedulingService.changeStatus(workload.getId(), WorkloadStatus.RUNNING);
        assertEquals(WorkloadStatus.RUNNING, running.getStatus());

        Workload done = schedulingService.changeStatus(workload.getId(), WorkloadStatus.COMPLETED);
        assertEquals(WorkloadStatus.COMPLETED, done.getStatus());
    }

    @DisplayName("허용되지 않는 전이는 400 이다")
    @Test
    void disallowed_transition_400() {
        Workload workload = workloadRepository.save(scheduledWorkload());

        BusinessException e = assertThrows(BusinessException.class,
                () -> schedulingService.changeStatus(workload.getId(), WorkloadStatus.COMPLETED));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("허용되지 않는 상태 전이"), e.getMessage());
    }

    @DisplayName("없는 워크로드의 상태 전이는 404 이다")
    @Test
    void missing_workload_transition_404() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> schedulingService.changeStatus(99999L, WorkloadStatus.RUNNING));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @DisplayName("종료 상태가 되면 예약 집계에서 빠진다")
    @Test
    void terminal_status_out_of_reservation() {
        Workload workload = workloadRepository.save(scheduledWorkload());

        assertTrue(reservedIds().contains(workload.getId()), "배치 직후에는 예약 대상이다");

        schedulingService.changeStatus(workload.getId(), WorkloadStatus.RUNNING);
        assertTrue(reservedIds().contains(workload.getId()), "실행 중에도 자원을 쓰므로 예약 유지");

        schedulingService.changeStatus(workload.getId(), WorkloadStatus.COMPLETED);
        assertFalse(reservedIds().contains(workload.getId()), "완료되면 예약이 풀린다");
    }

    // ── 리소스 종류별 조회 ──────────────────────────────────────

    @DisplayName("리소스 종류로 대기 워크로드를 고른다")
    @Test
    void pending_selected_by_resource_type() {
        Workload gpu = new Workload("gpu-job", 1, 1024);
        gpu.setResourceType(ResourceType.GPU);
        workloadRepository.save(gpu);
        workloadRepository.save(new Workload("legacy-job", 1, 1024));   // resourceType = null

        assertEquals(1, workloadRepository
                .findByStatusAndResourceTypeOrderByPriorityDescCreatedAtAsc(
                        WorkloadStatus.PENDING, ResourceType.GPU).size());
        assertEquals(1, workloadRepository
                .findByStatusAndResourceTypeIsNullOrderByPriorityDescCreatedAtAsc(
                        WorkloadStatus.PENDING).size(),
                "종류가 없는 기존 행은 별도 조회로 잡는다");
    }

    private java.util.List<Long> reservedIds() {
        return workloadRepository
                .findByStatusIn(java.util.List.of(WorkloadStatus.SCHEDULED, WorkloadStatus.RUNNING))
                .stream().map(Workload::getId).toList();
    }

    private Workload scheduledWorkload() {
        Workload workload = new Workload("job", 1, 1024);
        workload.assignTo("mdc-01", "node-1", 0.9, "배치됨");
        return workload;
    }

    private SchedulingPolicy active(String name, ResourceType type) {
        SchedulingPolicy policy = new SchedulingPolicy(name, name + " 정책");
        policy.setResourceType(type);
        policy.setActive(true);
        return policy;
    }
}

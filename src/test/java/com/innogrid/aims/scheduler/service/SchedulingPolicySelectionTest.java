package com.innogrid.aims.scheduler.service;

import org.junit.jupiter.api.BeforeEach;
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

    @Test
    void 종류별_활성_정책이_공통보다_우선한다() {
        policyRepository.save(active("common", null));
        policyRepository.save(active("gpu", ResourceType.GPU));

        assertEquals("gpu", schedulingService.resolvePolicy(ResourceType.GPU).getName());
        assertEquals("common", schedulingService.resolvePolicy(ResourceType.VM).getName(),
                "종류별 정책이 없으면 공통으로 내려온다");
    }

    @Test
    void 종류를_지정하지_않으면_공통_정책을_쓴다() {
        policyRepository.save(active("common", null));
        policyRepository.save(active("gpu", ResourceType.GPU));

        assertEquals("common", schedulingService.resolvePolicy().getName());
    }

    @Test
    void 활성_정책이_없으면_기본_정책을_만든다() {
        assertEquals(0, policyRepository.count());

        SchedulingPolicy created = schedulingService.resolvePolicy(ResourceType.NPU);

        assertEquals("default", created.getName());
        assertEquals(1, policyRepository.count(), "기본 정책이 저장된다");
    }

    @Test
    void 활성_정책이_둘이어도_예외가_나지_않는다() {
        policyRepository.save(active("common", null));
        policyRepository.save(active("gpu", ResourceType.GPU));

        assertDoesNotThrow(() -> schedulingService.resolvePolicy(ResourceType.GPU));
        assertDoesNotThrow(() -> schedulingService.resolvePolicy(ResourceType.CONTAINER));
    }

    // ── 상태 전이 ───────────────────────────────────────────────

    @Test
    void 상태_전이가_저장된다() {
        Workload workload = workloadRepository.save(scheduledWorkload());

        Workload running = schedulingService.changeStatus(workload.getId(), WorkloadStatus.RUNNING);
        assertEquals(WorkloadStatus.RUNNING, running.getStatus());

        Workload done = schedulingService.changeStatus(workload.getId(), WorkloadStatus.COMPLETED);
        assertEquals(WorkloadStatus.COMPLETED, done.getStatus());
    }

    @Test
    void 허용되지_않는_전이는_400_이다() {
        Workload workload = workloadRepository.save(scheduledWorkload());

        BusinessException e = assertThrows(BusinessException.class,
                () -> schedulingService.changeStatus(workload.getId(), WorkloadStatus.COMPLETED));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertTrue(e.getMessage().contains("허용되지 않는 상태 전이"), e.getMessage());
    }

    @Test
    void 없는_워크로드의_상태_전이는_404_이다() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> schedulingService.changeStatus(99999L, WorkloadStatus.RUNNING));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void 종료_상태가_되면_예약_집계에서_빠진다() {
        Workload workload = workloadRepository.save(scheduledWorkload());

        assertTrue(reservedIds().contains(workload.getId()), "배치 직후에는 예약 대상이다");

        schedulingService.changeStatus(workload.getId(), WorkloadStatus.RUNNING);
        assertTrue(reservedIds().contains(workload.getId()), "실행 중에도 자원을 쓰므로 예약 유지");

        schedulingService.changeStatus(workload.getId(), WorkloadStatus.COMPLETED);
        assertFalse(reservedIds().contains(workload.getId()), "완료되면 예약이 풀린다");
    }

    // ── 리소스 종류별 조회 ──────────────────────────────────────

    @Test
    void 리소스_종류로_대기_워크로드를_고른다() {
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

package com.innogrid.aims.scheduler.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.entity.Workload.ResourceType;
import com.innogrid.aims.scheduler.entity.Workload.WorkloadStatus;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class SchedulingPolicySelectionTest {

    @Autowired private SchedulingService schedulingService;
    @Autowired private SchedulingPolicyRepository policyRepository;
    @Autowired private WorkloadRepository workloadRepository;
    /** 수집기만 목 객체로 — 모의 배치 응답의 정책 이름을 보려면 노드가 한 대 있어야 한다. */
    @MockitoBean NodeResourceCollector collector;

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

        assertEquals("common", schedulingService.resolvePolicy(null).getName());
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

    // ── 응답의 적용 정책 이름 ──────────────────────────────────

    @DisplayName("모의 배치 응답은 적용된 정책 이름을 싣고, 종류별 활성 정책이 있으면 그 이름이다")
    @Test
    void dry_run_reports_applied_policy_name() {
        oneNode(10);
        policyRepository.save(active("common", null));
        Workload gpu = new Workload("prp-gpu", 1, 1024);
        gpu.setResourceType(ResourceType.GPU);
        gpu = workloadRepository.save(gpu);

        SchedulingResult common = schedulingService.dryRun(gpu.getId(), "weighted");
        assertTrue(common.success(), common.reason());
        assertEquals("common", common.policyName(), "GPU 활성 정책이 없으면 공통 정책");

        policyRepository.save(active("gpu-only", ResourceType.GPU));
        SchedulingResult typed = schedulingService.dryRun(gpu.getId(), "weighted");
        assertEquals("gpu-only", typed.policyName(), "종류별 활성 정책이 공통보다 먼저");
    }

    @DisplayName("임계에 걸려 실패한 모의 배치에도 적용 정책 이름이 남는다")
    @Test
    void failed_dry_run_keeps_policy_name() {
        oneNode(10);
        SchedulingPolicy strict = active("strict", null);
        strict.setMaxCpuUsagePercent(5.0);   // 노드 CPU 10% > 5% → 제외
        policyRepository.save(strict);
        Workload w = workloadRepository.save(new Workload("prp-fail", 1, 1024));

        SchedulingResult r = schedulingService.dryRun(w.getId(), "weighted");
        assertFalse(r.success(), "유일한 노드가 임계로 빠져 실패");
        assertEquals(ExclusionReason.CPU_USAGE_EXCEEDED, r.candidateScores().get(0).exclusionCode());
        assertEquals("strict", r.policyName(), "실패 결과에도 판정에 쓴 정책이 남는다");
    }

    @DisplayName("단건 배치 응답에도 적용 정책 이름이 있고 이력의 정책 이름과 같다")
    @Test
    void single_placement_reports_applied_policy_name() {
        oneNode(10);
        policyRepository.save(active("common", null));
        Workload w = workloadRepository.save(new Workload("prp-single", 1, 1024));

        SchedulingResult r = schedulingService.scheduleWorkload(w.getId(), "weighted");
        assertTrue(r.success(), r.reason());
        assertEquals("common", r.policyName(), "실배치 응답에도 적용 정책");
    }

    @DisplayName("종류를 비운 워크로드의 모의 배치는 VM 활성 정책을 쓴다")
    @Test
    void null_type_dry_run_uses_vm_policy() {
        oneNode(10);
        policyRepository.save(active("common", null));
        policyRepository.save(active("vm-only", ResourceType.VM));
        Workload w = workloadRepository.save(new Workload("prp-null", 1, 1024));

        SchedulingResult r = schedulingService.dryRun(w.getId(), "weighted");
        assertTrue(r.success(), r.reason());
        assertEquals("vm-only", r.policyName(), "종류가 없으면 VM 으로 읽어 VM 활성 정책을 고른다");
        assertNull(workloadRepository.findById(w.getId()).orElseThrow().getResourceType(), "저장값은 비운 채 유지");
    }

    @DisplayName("종류를 비운 워크로드의 단건 배치도 VM 활성 정책을 쓴다")
    @Test
    void null_type_single_placement_uses_vm_policy() {
        oneNode(10);
        policyRepository.save(active("common", null));
        policyRepository.save(active("vm-only", ResourceType.VM));
        Workload w = workloadRepository.save(new Workload("prp-null-single", 1, 1024));

        SchedulingResult r = schedulingService.scheduleWorkload(w.getId(), "weighted");
        assertTrue(r.success(), r.reason());
        assertEquals("vm-only", r.policyName(), "단건 배치도 모의 배치와 같은 규칙");
        assertNull(workloadRepository.findById(w.getId()).orElseThrow().getResourceType(), "저장값은 비운 채 유지");
    }

    @DisplayName("종류를 지정한 워크로드는 자기 종류의 정책을 그대로 쓴다")
    @Test
    void typed_workload_keeps_its_type_policy() {
        oneNode(10);
        policyRepository.save(active("common", null));
        policyRepository.save(active("vm-only", ResourceType.VM));
        SchedulingPolicy gpuOnly = policyRepository.save(active("gpu-only", ResourceType.GPU));
        Workload gpu = new Workload("prp-gpu-typed", 1, 1024);
        gpu.setResourceType(ResourceType.GPU);
        gpu = workloadRepository.save(gpu);

        assertEquals("gpu-only", schedulingService.dryRun(gpu.getId(), "weighted").policyName(), "GPU 는 GPU 정책");
        gpuOnly.setActive(false);
        policyRepository.save(gpuOnly);
        assertEquals("common", schedulingService.dryRun(gpu.getId(), "weighted").policyName(), "GPU 정책이 꺼지면 공통으로 — VM 정책으로 새지 않는다");
    }

    @DisplayName("활성 노드가 없어 후보 없이 실패해도 그 시점의 정책 이름이 있다")
    @Test
    void no_active_nodes_failure_keeps_policy_name() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(java.util.List.of(), java.util.List.of()));
        policyRepository.save(active("common", null));
        Workload w = workloadRepository.save(new Workload("prp-empty", 1, 1024));

        SchedulingResult dry = schedulingService.dryRun(w.getId(), "weighted");
        assertFalse(dry.success());
        assertTrue(dry.candidateScores().isEmpty(), "후보 자체가 없다");
        assertEquals("common", dry.policyName(), "후보가 없어도 정책은 이미 정해져 있다");
        SchedulingResult real = schedulingService.scheduleWorkload(w.getId(), "weighted");
        assertFalse(real.success());
        assertEquals("common", real.policyName());
    }

    private void oneNode(double cpuUsagePercent) {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                java.util.List.of(new NodeResource("mdc-01", "prp:9100", 16, 65536, 0, cpuUsagePercent, 20, 0, 0, 500, 0, 0, 0)),
                java.util.List.of()));
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

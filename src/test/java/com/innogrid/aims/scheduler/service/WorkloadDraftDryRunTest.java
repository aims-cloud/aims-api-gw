package com.innogrid.aims.scheduler.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.innogrid.aims.common.exception.BusinessException;
import com.innogrid.aims.scheduler.dto.WorkloadRequest;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.entity.Workload.ResourceType;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 저장 전 워크로드 모의 배치 — 초안은 어떤 행도 쓰지 않고, 정책 선택은 저장된 워크로드의 모의 배치와 같은 규칙이되
 * 정책이 하나도 없어도 {@code default} 를 만들지 않는다.
 */
@SpringBootTest
@Transactional
class WorkloadDraftDryRunTest {

    @Autowired private SchedulingService schedulingService;
    @Autowired private WorkloadService workloadService;
    @Autowired private SchedulingPolicyRepository policyRepository;
    @Autowired private WorkloadRepository workloadRepository;
    @Autowired private SchedulingHistoryRepository historyRepository;

    @MockitoBean NodeResourceCollector collector;

    private long workloadsBefore;
    private long historyBefore;

    @BeforeEach
    void clean() {
        policyRepository.deleteAll();
        workloadRepository.deleteAll();
        workloadsBefore = workloadRepository.count();
        historyBefore = historyRepository.count();
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                java.util.List.of(new NodeResource("mdc-01", "prp:9100", 16, 65536, 0, 10, 20, 0, 0, 500, 0, 0, 0)),
                java.util.List.of()));
    }

    private static WorkloadRequest form(String name, String type) {
        return new WorkloadRequest(name, "저장 전 모의 배치", 1, 1024, 0, 0, type, null, null, null, null, null, null);
    }

    private void nothingWritten() {
        assertEquals(workloadsBefore, workloadRepository.count(), "워크로드 행이 생기면 안 된다");
        assertEquals(historyBefore, historyRepository.count(), "배치 이력이 생기면 안 된다");
    }

    @DisplayName("종류를 비운 폼의 모의 배치는 VM 활성 정책을 쓰고 아무것도 저장하지 않는다")
    @Test
    void null_type_draft_uses_vm_policy() {
        policyRepository.save(active("common", null));
        policyRepository.save(active("vm-only", ResourceType.VM));

        SchedulingResult r = schedulingService.dryRun(workloadService.draft(form("draft-null", null)), "weighted");

        assertTrue(r.success(), r.reason());
        assertEquals("vm-only", r.policyName(), "종류가 없으면 VM 으로 읽어 VM 활성 정책");
        nothingWritten();
    }

    @DisplayName("정책이 하나도 없으면 default 값을 쓰되 저장하지 않는다")
    @Test
    void draft_without_any_policy_saves_none() {
        SchedulingResult r = schedulingService.dryRun(workloadService.draft(form("draft-nopolicy", null)), "weighted");

        assertEquals("default", r.policyName());
        assertEquals(0, policyRepository.count(), "저장 전 모의 배치는 default 행을 만들지 않는다");
        nothingWritten();
    }

    @DisplayName("저장된 워크로드의 모의 배치는 정책이 없으면 default 를 만들어 저장한다")
    @Test
    void saved_dry_run_still_creates_default() {
        Workload w = workloadService.create(form("saved", null));

        SchedulingResult r = schedulingService.dryRun(w.getId(), "weighted");

        assertEquals("default", r.policyName());
        assertEquals(1, policyRepository.count(), "기존 경로의 default 생성은 그대로");
    }

    @DisplayName("초안 검증은 생성과 같은 400 과 문구를 낸다")
    @Test
    void draft_rejects_like_create() {
        WorkloadRequest bad = new WorkloadRequest("cpu-zero", null, 0, 1024, null, null, null, null, null, null, null, null, null);

        BusinessException d = assertThrows(BusinessException.class, () -> workloadService.draft(bad));
        BusinessException c = assertThrows(BusinessException.class, () -> workloadService.create(bad));

        assertEquals(HttpStatus.BAD_REQUEST, d.getStatus());
        assertEquals(c.getMessage(), d.getMessage(), "초안과 생성의 사유 문구가 같다");
        nothingWritten();
    }

    @DisplayName("같은 폼이면 저장 전 모의 배치와 저장된 워크로드의 모의 배치 결과가 같다")
    @Test
    void draft_matches_saved_dry_run() {
        policyRepository.save(active("common", null));
        WorkloadRequest f = form("same-form", "VM");

        SchedulingResult saved = schedulingService.dryRun(workloadService.create(f).getId(), "weighted");
        SchedulingResult draft = schedulingService.dryRun(workloadService.draft(f), "weighted");

        assertEquals(saved.success(), draft.success());
        assertEquals(saved.selectedMdcId(), draft.selectedMdcId());
        assertEquals(saved.selectedNodeId(), draft.selectedNodeId());
        assertEquals(saved.finalScore(), draft.finalScore(), 1e-9);
        assertEquals(saved.reason(), draft.reason());
        assertEquals(saved.candidateScores(), draft.candidateScores(), "후보 점수 목록까지 같다");
        assertEquals(saved.policyName(), draft.policyName());
    }

    @DisplayName("모르는 엔진은 저장된 모의 배치와 같은 400 문구다")
    @Test
    void unknown_engine_is_400() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> schedulingService.dryRun(workloadService.draft(form("engine", null)), "nope"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertEquals("알 수 없는 스케줄링 엔진입니다: nope", e.getMessage());
        nothingWritten();
    }

    private SchedulingPolicy active(String name, ResourceType type) {
        SchedulingPolicy policy = new SchedulingPolicy(name, name + " 정책");
        policy.setResourceType(type);
        policy.setActive(true);
        return policy;
    }
}

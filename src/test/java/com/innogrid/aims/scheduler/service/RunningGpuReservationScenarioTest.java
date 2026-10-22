package com.innogrid.aims.scheduler.service;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.innogrid.aims.scheduler.engine.constraint.ExclusionReason;
import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 모의 메트릭 모드에서 실행 중 워크로드의 GPU 예약 — 배치 뒤 메트릭 지연(10분)이 지나도 쓰고 있는 카드는 끝 상태까지 묶인다.
 *
 * <p>대상 노드는 busan-03(카드 2장 · IPMI 가 없어 전력·온도가 0 이라 그 제약에 걸리지 않음)이다. 다른 GPU 노드 seoul-02 는
 * 전력 사양 원장이 비어 있으면 기본 정격 500W 로 잡혀 전력 여유 부족으로 빠지므로, 카드 2장 배치는 busan-03 으로 간다.
 * 카드 2장을 요구하는 워크로드를 실제로 배치하고 실행 중 콜백을 보낸 뒤, 배치 시각을 30분 앞당겨 메트릭 지연이 지난
 * 상태를 만든다. 기다리지 않으므로 경계와 20분 떨어져 시각에 흔들리지 않는다.</p>
 */
@SpringBootTest
@TestPropertySource(properties = "aims.scheduler.mock-enabled=true")
class RunningGpuReservationScenarioTest {

    private static final String PREFIX = "gpures-";
    private static final String NODE = "busan-03:9100";

    @Autowired SchedulingService schedulingService;
    @Autowired PlacementDispatchService dispatchService;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired SchedulingHistoryRepository historyRepository;

    @AfterEach
    void clean() {
        historyRepository.findAll().stream().filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith(PREFIX)).forEach(historyRepository::delete);
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith(PREFIX)).forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
    }

    @DisplayName("메트릭 지연이 지난 실행중 워크로드의 GPU 카드는 다음 배치에 다시 내주지 않는다")
    @Test
    void running_gpu_cards_are_not_offered_again_after_metric_lag() {
        Running running = runningGpuWorkloadOnGpuNode();

        PlacementContext context = schedulingService.createContext(policy());
        NodeResource node = context.node(NODE).orElseThrow();
        assertEquals(2, node.getReservedGpu(), "쓰고 있는 카드 2장이 예약으로 남는다");
        assertEquals(0, node.availableGpu());
        assertEquals(running.reservedCpuBefore(), node.getReservedCpu(), "CPU 는 사용률 메트릭이 반영하므로 예약에 다시 넣지 않는다");

        SchedulingResult result = schedulingService.evaluateIn(draft(), "weighted", context);
        SchedulingResult.NodeScore busan = scoreOf(result);
        assertFalse(busan.eligible(), "같은 카드를 두 번 내주면 안 된다");
        assertEquals(ExclusionReason.INSUFFICIENT_RESOURCE, busan.exclusionCode(), busan.exclusionReason());
        assertNotEquals(NODE, result.selectedNodeId());
    }

    @DisplayName("실행중 워크로드를 상태 변경으로 끝내면 잡던 GPU 카드가 풀린다")
    @Test
    void operator_completion_frees_running_gpu_cards() {
        Running running = runningGpuWorkloadOnGpuNode();

        schedulingService.changeStatus(running.workload().getId(), Workload.WorkloadStatus.COMPLETED);

        PlacementContext context = schedulingService.createContext(policy());
        assertEquals(2, context.node(NODE).orElseThrow().availableGpu(), "끝 상태가 되면 예약 대상에서 빠진다");
        SchedulingResult result = schedulingService.evaluateIn(draft(), "weighted", context);
        assertTrue(scoreOf(result).eligible(), scoreOf(result).exclusionReason());
        assertTrue(result.success(), result.reason());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private record Running(Workload workload, int reservedCpuBefore) {}

    /**
     * 카드 2장을 요구하는 워크로드를 busan-03 에 배치하고 실행 중 콜백을 보낸 뒤, 배치 시각을 30분 앞당긴다.
     * 엔티티가 배치 시각을 직접 바꿀 수 없어(배정 때 현재 시각을 넣는다) 필드를 바꿔 저장한다 — 전달 기록의 요청 시각은
     * 그대로라 현재 결정으로 남는다.
     */
    private Running runningGpuWorkloadOnGpuNode() {
        NodeResource before = schedulingService.createContext(policy()).node(NODE).orElseThrow();
        assertEquals(2, before.availableGpu(), "사전조건 — 다른 테스트가 남긴 GPU 예약이 없어야 한다");
        int reservedCpuBefore = before.getReservedCpu();

        Workload workload = new Workload(PREFIX + "a", 2, 4096);
        workload.setRequiredGpu(2);
        workload.setResourceType(Workload.ResourceType.GPU);
        workload = workloadRepository.saveAndFlush(workload);
        SchedulingResult placed = schedulingService.scheduleWorkload(workload.getId(), "weighted");
        assertTrue(placed.success(), placed.reason());
        assertEquals(NODE, placed.selectedNodeId());

        PlacementDispatch dispatch = dispatchRepository.findByWorkloadIdOrderByRequestedAtDesc(workload.getId()).get(0);
        assertEquals(PlacementDispatch.DispatchStatus.ACCEPTED, dispatch.getStatus());
        dispatchService.applyResult(dispatch.getId(), "RUNNING", PREFIX + "job", null);

        Workload running = workloadRepository.findById(workload.getId()).orElseThrow();
        assertEquals(Workload.WorkloadStatus.RUNNING, running.getStatus());
        ReflectionTestUtils.setField(running, "scheduledAt", OffsetDateTime.now().minusMinutes(30));
        return new Running(workloadRepository.saveAndFlush(running), reservedCpuBefore);
    }

    /** 카드 1장을 요구하는 저장하지 않은 초안. */
    private static Workload draft() {
        Workload draft = new Workload(PREFIX + "b", 1, 1024);
        draft.setRequiredGpu(1);
        draft.setResourceType(Workload.ResourceType.GPU);
        return draft;
    }

    /** 저장하지 않은 정책 — 공유 DB 에 다른 테스트가 남긴 정책이 판정에 끼지 않는다. */
    private static SchedulingPolicy policy() {
        return new SchedulingPolicy(PREFIX + "p", "d");
    }

    private static SchedulingResult.NodeScore scoreOf(SchedulingResult result) {
        return result.candidateScores().stream().filter(s -> NODE.equals(s.nodeId())).findFirst().orElseThrow();
    }
}

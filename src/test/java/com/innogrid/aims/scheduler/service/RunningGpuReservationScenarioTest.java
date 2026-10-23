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
 * 모의 메트릭 모드에서 실행 중 워크로드의 예약 — 쓰고 있는 GPU 카드는 끝 상태까지 묶이고, 늦게 시작한 워크로드의 CPU 는 시작 직후 남는다.
 *
 * <p>카드를 쓰는 세 테스트의 대상 노드는 busan-03(카드 2장 · IPMI 가 없어 전력·온도가 0 이라 그 제약에 걸리지 않음)이다. 다른 GPU 노드 seoul-02 는
 * 전력 사양 원장이 비어 있으면 기본 정격 500W 로 잡혀 전력 여유 부족으로 빠지므로, 카드 2장 배치는 busan-03 으로 간다.
 * 카드 2장을 요구하는 워크로드를 실제로 배치하고 실행 중 콜백을 보낸 뒤, 배치 시각(과 필요하면 시작 시각 — RUNNING 이 된
 * 전달 기록의 응답 시각)을 30분 앞당겨 메트릭 지연이 지난 상태를 만든다. 기다리지 않으므로 앞당긴 시각은 경계와 20분, 방금
 * 시작한 시각은 경계와 약 10분 떨어져 시각에 흔들리지 않는다.</p>
 *
 * <p>카드가 0장으로 잡힌 busan-01 에는 카드 1장을 쓰는 실행 중 워크로드를 직접 배정해, 예약 GPU 가 총 카드보다 많은
 * 상태(GPU 수집이 끊긴 노드)를 만든다. 그 노드가 GPU 를 요구하지 않는 배치를 받는지 본다. 판 전체 평가가 공유 냉각
 * 상태에 표시를 남기지 않게 온도 인지를 끈 정책으로 평가한다.</p>
 */
@SpringBootTest
@TestPropertySource(properties = "aims.scheduler.mock-enabled=true")
class RunningGpuReservationScenarioTest {

    private static final String PREFIX = "gpures-";
    private static final String NODE = "busan-03:9100";
    private static final String ZERO_CARD_NODE = "busan-01:9100";

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
        Running running = runningGpuWorkloadOnGpuNode(true);

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
        Running running = runningGpuWorkloadOnGpuNode(true);

        schedulingService.changeStatus(running.workload().getId(), Workload.WorkloadStatus.COMPLETED);

        PlacementContext context = schedulingService.createContext(policy());
        assertEquals(2, context.node(NODE).orElseThrow().availableGpu(), "끝 상태가 되면 예약 대상에서 빠진다");
        SchedulingResult result = schedulingService.evaluateIn(draft(), "weighted", context);
        assertTrue(scoreOf(result).eligible(), scoreOf(result).exclusionReason());
        assertTrue(result.success(), result.reason());
    }

    @DisplayName("배치 뒤 늦게 시작한 실행중 워크로드는 시작 직후 CPU 예약이 남는다")
    @Test
    void late_started_running_keeps_cpu_reserved_after_start() {
        Running running = runningGpuWorkloadOnGpuNode(false);

        NodeResource node = schedulingService.createContext(policy()).node(NODE).orElseThrow();
        assertEquals(running.reservedCpuBefore() + 2, node.getReservedCpu(),
                "배치 30분 뒤 방금 시작했다 — 아직 사용률에 없으니 CPU 2 를 예약으로 잡는다");
        assertEquals(2, node.getReservedGpu());
    }

    @DisplayName("카드가 0장으로 잡힌 노드에 실행중 GPU 워크로드가 있어도 GPU 를 요구하지 않는 배치는 받는다")
    @Test
    void cpu_only_draft_not_blocked_by_cards_reserved_beyond_total() {
        SchedulingPolicy policy = policy();
        policy.setTemperatureAwareEnabled(false);
        NodeResource before = schedulingService.createContext(policy).node(ZERO_CARD_NODE).orElseThrow();
        assertEquals(0, before.getReservedGpu(), "사전조건 — 다른 테스트가 남긴 GPU 예약이 없어야 한다");
        assertEquals(0, before.availableGpu());

        Workload workload = new Workload(PREFIX + "c", 2, 4096);
        workload.setRequiredGpu(1);
        workload.setResourceType(Workload.ResourceType.GPU);
        workload.assignTo("mdc-busan", ZERO_CARD_NODE, 0, "카드가 잡히던 때의 배치");
        workload = workloadRepository.saveAndFlush(workload);
        schedulingService.changeStatus(workload.getId(), Workload.WorkloadStatus.RUNNING);
        Workload running = workloadRepository.findById(workload.getId()).orElseThrow();
        ReflectionTestUtils.setField(running, "scheduledAt", OffsetDateTime.now().minusMinutes(30));
        workloadRepository.saveAndFlush(running);

        PlacementContext context = schedulingService.createContext(policy);
        NodeResource node = context.node(ZERO_CARD_NODE).orElseThrow();
        assertEquals(1, node.getReservedGpu(), "실행 중 워크로드의 카드 1장이 예약으로 남는다");
        assertEquals(-1, node.availableGpu(), "수집이 카드를 0장으로 잡아 가용 GPU 가 음수다");
        assertEquals(before.getReservedCpu(), node.getReservedCpu(), "시작 기록이 없어 배치 시각 기준 — 30분이 지나 CPU 는 예약에 없다");

        SchedulingResult.NodeScore free = scoreOf(schedulingService.evaluateIn(cpuDraft(), "weighted", context), ZERO_CARD_NODE);
        assertTrue(free.eligible(), free.exclusionReason());
        SchedulingResult.NodeScore gpu = scoreOf(schedulingService.evaluateIn(draft(), "weighted", context), ZERO_CARD_NODE);
        assertFalse(gpu.eligible(), "카드를 요구하는 배치는 그대로 막힌다");
        assertEquals(ExclusionReason.INSUFFICIENT_RESOURCE, gpu.exclusionCode(), gpu.exclusionReason());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private record Running(Workload workload, int reservedCpuBefore) {}

    /**
     * 카드 2장을 요구하는 워크로드를 busan-03 에 배치하고 실행 중 콜백을 보낸 뒤, 배치 시각을 30분 앞당긴다.
     * 엔티티가 배치 시각을 직접 바꿀 수 없어(배정 때 현재 시각을 넣는다) 필드를 바꿔 저장한다 — 전달 기록의 요청 시각은
     * 그대로라 현재 결정으로 남는다. {@code startLongAgo} 면 시작 시각(RUNNING 이 된 전달 기록의 응답 시각)도 30분
     * 앞당긴다. 콜백 뒤 기록을 다시 읽어 바꾼다 — 콜백 전에 읽은 사본(수락 상태)을 저장하면 상태가 되돌아간다.
     */
    private Running runningGpuWorkloadOnGpuNode(boolean startLongAgo) {
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
        if (startLongAgo) {
            PlacementDispatch started = dispatchRepository.findById(dispatch.getId()).orElseThrow();
            ReflectionTestUtils.setField(started, "respondedAt", OffsetDateTime.now().minusMinutes(30));
            dispatchRepository.saveAndFlush(started);
            assertEquals(PlacementDispatch.DispatchStatus.RUNNING,
                    dispatchRepository.findById(dispatch.getId()).orElseThrow().getStatus());
        }

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

    /** GPU 를 요구하지 않는 저장하지 않은 초안. */
    private static Workload cpuDraft() {
        return new Workload(PREFIX + "d", 1, 1024);
    }

    /** 저장하지 않은 정책 — 공유 DB 에 다른 테스트가 남긴 정책이 판정에 끼지 않는다. */
    private static SchedulingPolicy policy() {
        return new SchedulingPolicy(PREFIX + "p", "d");
    }

    private static SchedulingResult.NodeScore scoreOf(SchedulingResult result) {
        return scoreOf(result, NODE);
    }

    private static SchedulingResult.NodeScore scoreOf(SchedulingResult result, String nodeId) {
        return result.candidateScores().stream().filter(s -> nodeId.equals(s.nodeId())).findFirst().orElseThrow();
    }
}

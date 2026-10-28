package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.innogrid.aims.scheduler.dispatch.DispatcherRegistry;
import com.innogrid.aims.scheduler.dispatch.PlacementDispatcher;
import com.innogrid.aims.scheduler.dispatch.RecordingPlacementDispatcher;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.PlacementDispatchRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunInputRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 저장한 실행 입력만으로 그 회차의 배치 결정을 다시 낼 수 있는지 — 엔진 3종마다 워크로드별 선택 노드와 노드 배치
 * 스코어({@code finalScore})가 배치 이력과 오차 없이 같아야 한다.
 *
 * <pre>
 * 노드(수집 순서)  tie-b · tie-a  완전히 같은 두 대 — 동점이면 앞 노드가 이기므로 저장 순서가 결과를 정한다
 *                 hot            80℃ — 첫 평가에서 정책 상한 75℃ 를 넘어 루프 중 냉각 대기가 걸린다
 *                 pre            가장 한가한 노드 — 실행 전에 냉각 대기를 걸어 둔다(저장된 대기가 없으면 재생이 이 노드를 고른다)
 * 워크로드       우선순위가 다른 GPU 종류 3건(요구 GPU 0)
 * </pre>
 */
@SpringBootTest
class RunInputReplayTest {

    private static final String PREFIX = "prov-";
    private static final String TIE_A = "prov-tie-a:9100";
    private static final String TIE_B = "prov-tie-b:9100";
    private static final String HOT = "prov-hot:9100";
    private static final String PRE = "prov-pre:9100";

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingHistoryRepository historyRepository;
    @Autowired PlacementDispatchRepository dispatchRepository;
    @Autowired ScheduleRunInputRepository inputRepository;
    @Autowired ThermalModel thermalModel;
    @MockitoBean NodeResourceCollector collector;
    @MockitoBean DispatcherRegistry registry;

    @BeforeEach
    void acceptAll() {
        when(registry.current()).thenReturn(new RecordingPlacementDispatcher());
    }

    @AfterEach
    void clean() {
        List.of(TIE_A, TIE_B, HOT, PRE).forEach(thermalModel::clearCritical);
        historyRepository.findAll().stream().filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith(PREFIX)).forEach(historyRepository::delete);
        dispatchRepository.findAll().stream().filter(d -> d.getWorkloadName() != null && d.getWorkloadName().startsWith(PREFIX)).forEach(dispatchRepository::delete);
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith(PREFIX)).forEach(s -> scheduleService.delete(List.of(s.getId())));
    }

    @DisplayName("저장한 입력만으로 다시 돌리면 워크로드마다 선택 노드와 노드 배치 스코어가 배치 이력과 같다 — 동점 노드·실행 전 냉각 대기 포함")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"weighted", "power", "temperature"})
    void replay_from_stored_inputs_reproduces_decisions(String engine) {
        fourNodes();
        thermalModel.markCritical(PRE, 75);
        save("w1", 4, Workload.WorkloadPriority.HIGH);
        save("w2", 4, Workload.WorkloadPriority.NORMAL);
        save("w3", 4, Workload.WorkloadPriority.LOW);

        ScheduleRun run = executionService.runNow(schedule("s-" + engine, engine), "tester");

        RunInputCapture.RunInputs inputs = RunInputCapture.read(inputRepository.findByScheduleRunId(run.getId()).orElseThrow().getPayload());
        List<SchedulingHistory> history = historyRepository.findByScheduleRunIdOrderByCreatedAtAscIdAsc(run.getId());
        assertEquals(3, history.size());
        assertTrue(history.stream().anyMatch(h -> TIE_B.equals(h.getSelectedNodeId()) || TIE_A.equals(h.getSelectedNodeId())),
                "동점 노드가 한 번은 골라져야 순서 의존을 볼 수 있다: " + history.stream().map(SchedulingHistory::getSelectedNodeId).toList());

        assertReplayMatches(RunInputReplay.replay(inputs, history, Set.of()), history);
    }

    @DisplayName("실행 계층이 거부한 배치와 배치 직전에 건너뛴 워크로드도 결과 쪽 기록을 따라가면 같은 결정이 나온다")
    @Test
    void replay_follows_dispatch_rejection_and_skip() {
        twoTieNodes();
        Workload r1 = save("r1", 5, Workload.WorkloadPriority.HIGH);
        Workload r2 = save("r2", 5, Workload.WorkloadPriority.NORMAL);
        save("r3", 5, Workload.WorkloadPriority.LOW);
        // r1 을 거부하면서 r2 를 다른 경로가 배치한 것처럼 바꿔 둔다 — r2 는 배치 직전 다시 읽혀 건너뛰어진다
        when(registry.current()).thenReturn(new PlacementDispatcher() {
            @Override public String getMode() { return "test"; }
            @Override public Outcome dispatch(Request request) {
                if ((PREFIX + "r1").equals(request.workloadName())) {
                    Workload other = workloadRepository.findById(r2.getId()).orElseThrow();
                    other.assignTo("mdc-01", TIE_A, 0, "다른 경로");
                    workloadRepository.saveAndFlush(other);
                    return Outcome.rejected("노드 점검 중");
                }
                return Outcome.accepted(null, "ok");
            }
        });

        ScheduleRun run = executionService.runNow(schedule("s-reject", "weighted"), "tester");

        List<SchedulingHistory> history = historyRepository.findByScheduleRunIdOrderByCreatedAtAscIdAsc(run.getId());
        Set<Long> rejected = dispatchRepository.findByScheduleRunIdOrderByRequestedAtAsc(run.getId()).stream()
                .filter(d -> d.getStatus() == PlacementDispatch.DispatchStatus.REJECTED)
                .map(PlacementDispatch::getWorkloadId).collect(Collectors.toSet());
        assertEquals(Set.of(r1.getId()), rejected);
        RunInputCapture.RunInputs inputs = RunInputCapture.read(inputRepository.findByScheduleRunId(run.getId()).orElseThrow().getPayload());

        List<RunInputReplay.Step> steps = RunInputReplay.replay(inputs, history, rejected);

        assertTrue(steps.stream().anyMatch(s -> s.skipped() && s.workloadId().equals(r2.getId())), "r2 는 건너뛴 것으로 재생된다: " + steps);
        assertReplayMatches(steps, history);
        SchedulingHistory third = history.get(history.size() - 1);
        assertEquals(TIE_B, third.getSelectedNodeId(), "r1 의 예약이 되돌아가 r3 가 같은 노드를 받는다 — 되돌림을 재생하지 않으면 tie-a 가 나온다");
        List<RunInputReplay.Step> withoutRollback = RunInputReplay.replay(inputs, history, Set.of());
        assertNotEquals(TIE_B, withoutRollback.get(withoutRollback.size() - 1).result().selectedNodeId(),
                "거부 기록을 쓰지 않으면 재생이 어긋나야 이 테스트가 되돌림을 확인한다");
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private static void assertReplayMatches(List<RunInputReplay.Step> steps, List<SchedulingHistory> history) {
        assertEquals(history.size(), steps.size());
        for (int i = 0; i < steps.size(); i++) {
            RunInputReplay.Step step = steps.get(i);
            SchedulingHistory h = history.get(i);
            assertEquals(h.getWorkloadId(), step.workloadId(), "순서가 같아야 한다");
            if (step.skipped()) continue;
            assertEquals(h.getSelectedNodeId(), step.result().selectedNodeId(), "선택 노드 " + i);
            assertEquals(h.getFinalScore(), step.result().finalScore(), 0.0, "노드 배치 스코어 " + i);
            assertEquals(Boolean.valueOf(step.result().success()), h.getSuccess(), "성공 여부 " + i);
        }
    }

    private void fourNodes() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(
                tie(TIE_B), tie(TIE_A),
                new NodeResource("mdc-01", HOT, 16, 65_536, 0, 10, 10, 0, 150, 500, 80, 0, 0),
                new NodeResource("mdc-01", PRE, 16, 65_536, 0, 5, 5, 0, 120, 500, 40, 0, 0)), List.of()));
    }

    private void twoTieNodes() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(
                new NodeResource("mdc-01", TIE_B, 8, 65_536, 0, 10, 20, 0, 200, 500, 50, 0, 0),
                new NodeResource("mdc-01", TIE_A, 8, 65_536, 0, 10, 20, 0, 200, 500, 50, 0, 0)), List.of()));
    }

    private static NodeResource tie(String nodeId) {
        return new NodeResource("mdc-01", nodeId, 16, 65_536, 0, 30, 30, 0, 200, 500, 50, 0, 0);
    }

    private long schedule(String key, String engine) {
        return scheduleService.create(new ScheduleRequest(PREFIX + key, PREFIX + key, Workload.ResourceType.GPU, "0 0 2 * * ?",
                false, true, PREFIX, engine, null, null, null, null)).id();
    }

    private Workload save(String name, int cpu, Workload.WorkloadPriority priority) {
        Workload w = new Workload(PREFIX + name, cpu, 1024);
        w.setResourceType(Workload.ResourceType.GPU);
        w.setPriority(priority);
        return workloadRepository.saveAndFlush(w);
    }
}

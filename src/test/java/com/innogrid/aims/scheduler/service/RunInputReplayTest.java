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
import org.springframework.transaction.support.TransactionTemplate;

import com.innogrid.aims.scheduler.dispatch.DispatcherRegistry;
import com.innogrid.aims.scheduler.dispatch.PlacementDispatcher;
import com.innogrid.aims.scheduler.dispatch.RecordingPlacementDispatcher;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleRunEngineReplayResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRunReplayResponse;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
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

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 저장한 실행 입력만으로 그 회차의 배치 결정을 다시 낼 수 있는지 — 엔진 3종마다 워크로드별 선택 노드와 노드 배치
 * 스코어({@code finalScore})가 배치 이력과 오차 없이 같아야 한다.
 *
 * <pre>
 * 노드(수집 순서)  tie-b · tie-a  완전히 같은 두 대 — 동점이면 앞 노드가 이기므로 저장 순서가 결과를 정한다
 *                 hot            80℃ — 첫 평가에서 정책 상한 75℃를 넘어 루프 중 냉각 대기가 걸린다
 *                 pre            가장 한가한 노드 — 실행 전에 냉각 대기를 걸어 둔다(저장된 대기가 없으면 재생이 이 노드를 고른다)
 * 워크로드       우선순위가 다른 GPU 종류 3건(요구 GPU 0)
 * </pre>
 *
 * <p>실행 재생 API의 판정({@link ScheduleService#replay})과 엔진별 재계산({@link ScheduleService#replayEngines})도 여기서 서비스를 직접 불러 본다 — 실제 실행이 남긴 입력·이력·전달
 * 기록이 있어야 해서 이 컨텍스트를 함께 쓴다.</p>
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
    @Autowired WorkloadService workloadService;
    @Autowired EngineRegistry engineRegistry;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;
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
        // r1을 거부하면서 r2를 다른 경로가 배치한 것처럼 바꿔 둔다 — r2는 배치 직전 다시 읽혀 건너뛰어진다
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

    @DisplayName("실행 재생은 엔진마다 모든 워크로드를 MATCH 로 판정하고 운영 냉각 대기를 건드리지 않는다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"weighted", "power", "temperature"})
    void replay_service_matches_every_workload(String engine) {
        fourNodes();
        thermalModel.markCritical(PRE, 75);
        save("w1", 4, Workload.WorkloadPriority.HIGH);
        save("w2", 4, Workload.WorkloadPriority.NORMAL);
        save("w3", 4, Workload.WorkloadPriority.LOW);
        long scheduleId = schedule("a-" + engine, engine);
        ScheduleRun run = executionService.runNow(scheduleId, "tester");
        // 실행이 hot에 건 대기를 지워 둔다 — 같은 상한의 대기가 이미 있으면 다시 걸어도 그대로라, 남겨 두면 재생이 운영 모델에 대기를 걸어도 아래 비교가 같다
        thermalModel.clearCritical(HOT);
        String marksBefore = String.valueOf(thermalModel.cooldownMarks());   // 안쪽 맵까지 문자열로 떠 둔다

        ScheduleRunReplayResponse replay = scheduleService.replay(scheduleId, run.getId());

        assertEquals(ScheduleRunReplayResponse.Verdict.MATCH, replay.verdict(), "판정: " + replay.steps());
        assertEquals(3, replay.total());
        assertEquals(3, replay.matched());
        assertEquals(engine, replay.engineId());
        assertEquals(marksBefore, String.valueOf(thermalModel.cooldownMarks()), "재생은 엔진·모델을 새로 만들어 운영 냉각 대기를 바꾸지 않는다");
    }

    @DisplayName("실행 계층에 닿지 못한(UNAVAILABLE) 배치도 예약을 되돌려 재생해야 다음 워크로드가 같은 노드를 받는다")
    @Test
    void unavailable_dispatch_is_rolled_back_in_replay() {
        twoTieNodes();
        save("u1", 5, Workload.WorkloadPriority.HIGH);
        save("u2", 5, Workload.WorkloadPriority.NORMAL);
        when(registry.current()).thenReturn(new PlacementDispatcher() {
            @Override public String getMode() { return "test"; }
            @Override public Outcome dispatch(Request request) {
                return (PREFIX + "u1").equals(request.workloadName()) ? Outcome.unavailable("연결 끊김") : Outcome.accepted(null, "ok");
            }
        });
        long scheduleId = schedule("s-unavailable", "weighted");
        ScheduleRun run = executionService.runNow(scheduleId, "tester");
        List<SchedulingHistory> history = historyRepository.findByScheduleRunIdOrderByCreatedAtAscIdAsc(run.getId());
        assertEquals(TIE_B, history.get(1).getSelectedNodeId(), "u1 의 예약이 되돌아가 u2 가 같은 노드를 받는다");

        ScheduleRunReplayResponse replay = scheduleService.replay(scheduleId, run.getId());

        assertEquals(ScheduleRunReplayResponse.Verdict.MATCH, replay.verdict(), "판정: " + replay.steps());
        RunInputCapture.RunInputs inputs = RunInputCapture.read(inputRepository.findByScheduleRunId(run.getId()).orElseThrow().getPayload());
        assertEquals(ScheduleRunReplayResponse.Verdict.MISMATCH,
                RunInputReplay.compare(RunInputReplay.replay(inputs, history, Set.of()), history, Set.of()).verdict(),
                "되돌림을 빼면 어긋나야 이 테스트가 UNAVAILABLE 되돌림을 확인한다");
    }

    @DisplayName("거부된 배치와 배치 직전에 건너뛴 워크로드가 있는 실행도 재생하면 MATCH 이고 건너뛴 건은 SKIPPED 다")
    @Test
    void replay_service_follows_rejection_and_skip() {
        Workload[] r = rejectFirstAndMoveSecond();
        long scheduleId = schedule("s-api-reject", "weighted");
        ScheduleRun run = executionService.runNow(scheduleId, "tester");

        ScheduleRunReplayResponse replay = scheduleService.replay(scheduleId, run.getId());

        assertEquals(ScheduleRunReplayResponse.Verdict.MATCH, replay.verdict(), "판정: " + replay.steps());
        assertEquals(1, replay.skipped());
        assertEquals(ScheduleRunReplayResponse.Outcome.SKIPPED,
                replay.steps().stream().filter(s -> s.workloadId().equals(r[1].getId())).findFirst().orElseThrow().outcome());
    }

    @DisplayName("배치 이력 한 행의 노드 배치 스코어가 다르면 그 워크로드만 MISMATCH 로 판정한다")
    @Test
    void changed_score_in_history_is_a_mismatch() {
        fourNodes();
        save("m1", 4, Workload.WorkloadPriority.HIGH);
        save("m2", 4, Workload.WorkloadPriority.NORMAL);
        long scheduleId = schedule("s-mismatch", "weighted");
        ScheduleRun run = executionService.runNow(scheduleId, "tester");
        SchedulingHistory first = historyRepository.findByScheduleRunIdOrderByCreatedAtAscIdAsc(run.getId()).get(0);
        tx.executeWithoutResult(st -> em.createQuery("update SchedulingHistory h set h.finalScore = h.finalScore + 0.001 where h.id = :id")
                .setParameter("id", first.getId()).executeUpdate());

        ScheduleRunReplayResponse replay = scheduleService.replay(scheduleId, run.getId());

        assertEquals(ScheduleRunReplayResponse.Verdict.MISMATCH, replay.verdict());
        assertEquals(1, replay.mismatched());
        assertEquals(ScheduleRunReplayResponse.Outcome.MISMATCH, replay.steps().get(0).outcome());
        assertEquals(replay.steps().get(0).recordedNodeId(), replay.steps().get(0).replayedNodeId(), "노드는 같고 스코어만 다르다");
    }

    @DisplayName("거부된 워크로드를 지워 전달 기록이 사라지면 그 뒤를 확인할 수 없어 INCOMPLETE 로 판정한다")
    @Test
    void deleting_a_rejected_workload_makes_replay_incomplete() {
        twoTieNodes();
        Workload d1 = save("d1", 5, Workload.WorkloadPriority.HIGH);
        save("d2", 5, Workload.WorkloadPriority.NORMAL);
        when(registry.current()).thenReturn(new PlacementDispatcher() {
            @Override public String getMode() { return "test"; }
            @Override public Outcome dispatch(Request request) {
                return (PREFIX + "d1").equals(request.workloadName()) ? Outcome.rejected("노드 점검 중") : Outcome.accepted(null, "ok");
            }
        });
        long scheduleId = schedule("s-deleted", "weighted");
        ScheduleRun run = executionService.runNow(scheduleId, "tester");
        workloadService.delete(d1.getId());   // 전달 기록만 함께 지운다 — 배치 이력은 남는다

        ScheduleRunReplayResponse replay = scheduleService.replay(scheduleId, run.getId());

        assertEquals(ScheduleRunReplayResponse.Verdict.INCOMPLETE, replay.verdict(), "판정: " + replay.steps());
        assertEquals(1, replay.missingDispatchRecords());
        assertEquals(ScheduleRunReplayResponse.Outcome.MISMATCH, replay.steps().get(1).outcome(),
                "되돌림을 몰라 d2 는 다른 노드로 재생된다 — 그래도 판정은 MISMATCH 가 아니다");
    }

    @DisplayName("배치 이력 행이 없는 워크로드는 NOT_RECORDED 이고 판정은 INCOMPLETE 다")
    @Test
    void missing_history_row_is_not_recorded() {
        fourNodes();
        save("n1", 4, Workload.WorkloadPriority.HIGH);
        save("n2", 4, Workload.WorkloadPriority.NORMAL);
        long scheduleId = schedule("s-norecord", "weighted");
        ScheduleRun run = executionService.runNow(scheduleId, "tester");
        List<SchedulingHistory> history = historyRepository.findByScheduleRunIdOrderByCreatedAtAscIdAsc(run.getId());
        historyRepository.delete(history.get(history.size() - 1));

        ScheduleRunReplayResponse replay = scheduleService.replay(scheduleId, run.getId());

        assertEquals(ScheduleRunReplayResponse.Verdict.INCOMPLETE, replay.verdict());
        assertEquals(1, replay.notRecorded());
        assertEquals(ScheduleRunReplayResponse.Outcome.NOT_RECORDED, replay.steps().get(1).outcome());
        assertNull(replay.steps().get(1).recordedSuccess());
    }

    @DisplayName("재생기가 다시 만드는 엔진 id 는 엔진 레지스트리의 id 와 같다 — 엔진을 더하면 재생기도 함께 고친다")
    @Test
    void replayable_engines_match_the_engine_registry() {
        Set<String> registered = engineRegistry.describe().stream().map(e -> e.get("id")).collect(Collectors.toSet());
        assertEquals(registered, RunInputReplay.ENGINES);
    }

    @DisplayName("엔진별 재계산은 실제로 쓴 엔진 열이 실행 재생과 같고 모두 배치 이력과 같으며, 엔진 순서가 고정되고 운영 냉각 대기를 건드리지 않는다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"weighted", "power", "temperature"})
    void engine_replay_keeps_the_recorded_engine_column_equal_to_replay(String engine) {
        fourNodes();
        thermalModel.markCritical(PRE, 75);
        save("e1", 4, Workload.WorkloadPriority.HIGH);
        save("e2", 4, Workload.WorkloadPriority.NORMAL);
        save("e3", 4, Workload.WorkloadPriority.LOW);
        long scheduleId = schedule("b-" + engine, engine);
        ScheduleRun run = executionService.runNow(scheduleId, "tester");
        thermalModel.clearCritical(HOT);   // 실행이 건 대기를 지워 둔다 — 재생이 운영 모델에 대기를 걸면 아래 비교가 어긋난다
        String marksBefore = String.valueOf(thermalModel.cooldownMarks());

        ScheduleRunEngineReplayResponse byEngine = scheduleService.replayEngines(scheduleId, run.getId());
        ScheduleRunReplayResponse replay = scheduleService.replay(scheduleId, run.getId());

        assertEquals(ScheduleRunReplayResponse.Verdict.MATCH, byEngine.replayVerdict());
        assertEquals(engine, byEngine.recordedEngineId());
        assertEquals(RunInputReplay.ENGINE_ORDER, byEngine.engines().stream().map(ScheduleRunEngineReplayResponse.Engine::engineId).toList());
        ScheduleRunEngineReplayResponse.Engine recorded = byEngine.engines().stream().filter(ScheduleRunEngineReplayResponse.Engine::recorded)
                .findFirst().orElseThrow();
        assertEquals(engine, recorded.engineId());
        assertEquals(3, recorded.sameAsRecorded(), "실제로 쓴 엔진은 배치 이력과 모두 같다");
        int at = RunInputReplay.ENGINE_ORDER.indexOf(engine);
        for (int i = 0; i < 3; i++) {
            assertEquals(replay.steps().get(i).replayedNodeId(), byEngine.steps().get(i).choices().get(at).nodeId(),
                    "실제로 쓴 엔진 열은 실행 재생의 선택과 같다 " + i);
        }
        assertEquals(marksBefore, String.valueOf(thermalModel.cooldownMarks()), "엔진별 재계산도 운영 냉각 대기를 바꾸지 않는다");
    }

    @DisplayName("엔진마다 따로 조립해 다시 계산한다 — 온도 엔진은 시원한 노드를, 가중치 엔진은 한가한 노드를 고르고 각 열은 그 엔진 단독 재생과 같다")
    @Test
    void engine_replay_builds_each_engine_separately() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(
                new NodeResource("mdc-01", PRE, 16, 65_536, 0, 60, 60, 0, 300, 500, 30, 0, 0),
                new NodeResource("mdc-01", HOT, 16, 65_536, 0, 5, 5, 0, 110, 500, 70, 0, 0)), List.of()));
        save("s1", 2, Workload.WorkloadPriority.NORMAL);
        long scheduleId = schedule("b-separate", "weighted");
        ScheduleRun run = executionService.runNow(scheduleId, "tester");

        ScheduleRunEngineReplayResponse byEngine = scheduleService.replayEngines(scheduleId, run.getId());

        RunInputCapture.RunInputs inputs = RunInputCapture.read(inputRepository.findByScheduleRunId(run.getId()).orElseThrow().getPayload());
        List<SchedulingHistory> history = historyRepository.findByScheduleRunIdOrderByCreatedAtAscIdAsc(run.getId());
        List<ScheduleRunEngineReplayResponse.Choice> choices = byEngine.steps().get(0).choices();
        for (int i = 0; i < RunInputReplay.ENGINE_ORDER.size(); i++) {
            String engine = RunInputReplay.ENGINE_ORDER.get(i);
            assertEquals(RunInputReplay.replay(inputs, engine, history, Set.of()).get(0).result().selectedNodeId(), choices.get(i).nodeId(),
                    engine + " 열은 그 엔진만 따로 다시 계산한 선택과 같다");
        }
        assertEquals(HOT, choices.get(RunInputReplay.ENGINE_ORDER.indexOf("weighted")).nodeId(), "가중치 엔진은 CPU 가 한가한 노드");
        assertEquals(PRE, choices.get(RunInputReplay.ENGINE_ORDER.indexOf("temperature")).nodeId(), "온도 엔진은 시원한 노드");
        assertEquals(ScheduleRunReplayResponse.Verdict.MATCH, byEngine.replayVerdict());
        assertEquals(RunInputReplay.ENGINE_ORDER, choices.stream().map(ScheduleRunEngineReplayResponse.Choice::engineId).toList(),
                "선택의 엔진 id 는 엔진 순서 그대로");
        assertEquals(1, byEngine.engines().get(RunInputReplay.ENGINE_ORDER.indexOf("weighted")).sameAsRecorded(),
                "실제로 쓴 가중치 엔진은 배치 이력과 같다");
        assertEquals(0, byEngine.engines().get(RunInputReplay.ENGINE_ORDER.indexOf("temperature")).sameAsRecorded(),
                "온도 엔진은 배치 이력과 다른 노드를 골라 같은 결정 수가 0");
    }

    @DisplayName("실행 계층에 닿지 못해 예약을 되돌린 워크로드는 모든 엔진에서 되돌려, 노드 한 대에서도 모든 엔진이 다음 워크로드를 배치한다")
    @Test
    void engine_replay_rolls_back_for_every_engine() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(
                new NodeResource("mdc-01", TIE_B, 8, 65_536, 0, 10, 20, 0, 200, 500, 50, 0, 0)), List.of()));
        save("v1", 5, Workload.WorkloadPriority.HIGH);
        save("v2", 5, Workload.WorkloadPriority.NORMAL);
        when(registry.current()).thenReturn(new PlacementDispatcher() {
            @Override public String getMode() { return "test"; }
            @Override public Outcome dispatch(Request request) {
                return (PREFIX + "v1").equals(request.workloadName()) ? Outcome.unavailable("연결 끊김") : Outcome.accepted(null, "ok");
            }
        });
        long scheduleId = schedule("b-rollback", "weighted");
        ScheduleRun run = executionService.runNow(scheduleId, "tester");

        ScheduleRunEngineReplayResponse byEngine = scheduleService.replayEngines(scheduleId, run.getId());

        assertEquals(ScheduleRunReplayResponse.Verdict.MATCH, byEngine.replayVerdict());
        assertEquals(1, byEngine.rolledBack());
        for (ScheduleRunEngineReplayResponse.Engine e : byEngine.engines()) {
            assertEquals(2, e.placed(), e.engineId() + " 도 v1 의 예약을 되돌려 8코어 노드에 v2(5코어)를 배치한다");
        }
    }

    @DisplayName("배치 직전에 건너뛴 워크로드는 모든 엔진에서 건너뛰고 엔진별 선택이 비어 있다")
    @Test
    void engine_replay_skips_for_every_engine() {
        Workload[] r = rejectFirstAndMoveSecond();
        long scheduleId = schedule("b-skip", "weighted");
        ScheduleRun run = executionService.runNow(scheduleId, "tester");

        ScheduleRunEngineReplayResponse byEngine = scheduleService.replayEngines(scheduleId, run.getId());

        assertEquals(1, byEngine.skipped());
        ScheduleRunEngineReplayResponse.Step skipped = byEngine.steps().stream().filter(s -> s.workloadId().equals(r[1].getId()))
                .findFirst().orElseThrow();
        assertTrue(skipped.skipped());
        assertTrue(skipped.choices().isEmpty());
        for (ScheduleRunEngineReplayResponse.Engine e : byEngine.engines()) {
            assertEquals(byEngine.total() - byEngine.skipped(), e.placed() + e.failed(), e.engineId() + " 는 건너뛴 워크로드를 계산하지 않는다");
        }
    }

    @DisplayName("배치 이력 한 행의 노드 배치 스코어가 다르면 엔진별 재계산의 재생 판정도 MISMATCH 다")
    @Test
    void engine_replay_verdict_follows_replay_comparison() {
        fourNodes();
        save("x1", 4, Workload.WorkloadPriority.HIGH);
        long scheduleId = schedule("b-mismatch", "weighted");
        ScheduleRun run = executionService.runNow(scheduleId, "tester");
        SchedulingHistory first = historyRepository.findByScheduleRunIdOrderByCreatedAtAscIdAsc(run.getId()).get(0);
        tx.executeWithoutResult(st -> em.createQuery("update SchedulingHistory h set h.finalScore = h.finalScore + 0.001 where h.id = :id")
                .setParameter("id", first.getId()).executeUpdate());

        ScheduleRunEngineReplayResponse byEngine = scheduleService.replayEngines(scheduleId, run.getId());

        assertEquals(ScheduleRunReplayResponse.Verdict.MISMATCH, byEngine.replayVerdict());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** r1을 거부하면서 r2를 다른 경로가 배치한 것처럼 바꾼다 — r2는 배치 직전 다시 읽혀 건너뛰어진다. */
    private Workload[] rejectFirstAndMoveSecond() {
        twoTieNodes();
        Workload r1 = save("r1", 5, Workload.WorkloadPriority.HIGH);
        Workload r2 = save("r2", 5, Workload.WorkloadPriority.NORMAL);
        save("r3", 5, Workload.WorkloadPriority.LOW);
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
        return new Workload[] {r1, r2};
    }

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

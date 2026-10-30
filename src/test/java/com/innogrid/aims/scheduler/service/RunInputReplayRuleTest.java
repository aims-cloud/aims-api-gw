package com.innogrid.aims.scheduler.service;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.ScheduleRunReplayResponse.Outcome;
import com.innogrid.aims.scheduler.dto.ScheduleRunReplayResponse.Verdict;
import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.PlacementDispatch.DispatchStatus;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import tools.jackson.core.JacksonException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 재생 규칙을 스프링 없이 본다 — 예약 되돌림 상태가 실행 쪽 판정과 같은지, 대조 판정이 확인할 수 없는 지점 앞뒤를 가르는지.
 */
class RunInputReplayRuleTest {

    @DisplayName("재생이 예약을 되돌리는 전달 상태는 실행이 배치를 실패로 낮추는 상태와 정확히 같다")
    @ParameterizedTest(name = "{0}")
    @EnumSource(DispatchStatus.class)
    void rollback_statuses_match_dispatch_downgrade(DispatchStatus status) throws Exception {
        SchedulingResult placed = new SchedulingResult(true, "mdc-01", "n1", 0.7, "최고 점수", List.of());

        boolean downgraded = !PlacementDispatchService.downgrade(placed, dispatch(1L, status)).success();

        assertEquals(downgraded, RunInputReplay.rollsBack(status),
                status + ": 실행은 " + (downgraded ? "예약을 되돌리는데" : "예약을 두는데") + " 재생은 반대로 한다");
    }

    @DisplayName("확인할 수 있는 구간에서 어긋나면 MISMATCH, 전달 기록이 없는 성공 배치 뒤에서만 어긋나면 INCOMPLETE")
    @Test
    void mismatch_after_a_missing_dispatch_record_is_incomplete() {
        List<SchedulingHistory> history = List.of(recorded(1L, "n1", 0.5), recorded(2L, "n1", 0.4));
        List<RunInputReplay.Step> steps = List.of(replayed(1L, "n1", 0.5), replayed(2L, "n2", 0.4));

        RunInputReplay.Report checked = RunInputReplay.compare(steps, history, Set.of());
        RunInputReplay.Report afterMissing = RunInputReplay.compare(steps, history, Set.of(1L));

        assertEquals(Verdict.MISMATCH, checked.verdict());
        assertEquals(List.of(Outcome.MATCH, Outcome.MISMATCH), checked.steps().stream().map(RunInputReplay.Compared::outcome).toList());
        assertEquals(Verdict.INCOMPLETE, afterMissing.verdict(), "1번의 예약을 되돌렸는지 몰라 2번의 어긋남은 판정할 수 없다");
        assertEquals(1, afterMissing.missingDispatchRecords());
        assertEquals(Verdict.MISMATCH, RunInputReplay.compare(steps, history, Set.of(2L)).verdict(),
                "2번 자신의 결정은 앞 상태가 확인된 위에서 계산돼 판정에 들어간다 — 모르는 것은 그 다음부터다");
    }

    @DisplayName("이력 행이 없는 워크로드 앞에서 어긋났으면 MISMATCH 다 — 그 뒤의 공백이 앞의 어긋남을 가리지 않는다")
    @Test
    void mismatch_before_a_missing_history_row_stays_mismatch() {
        List<SchedulingHistory> history = List.of(recorded(1L, "n1", 0.5));
        List<RunInputReplay.Step> steps = List.of(replayed(1L, "n1", 0.25), replayed(2L, "n1", 0.4));

        RunInputReplay.Report report = RunInputReplay.compare(steps, history, Set.of());

        assertEquals(Verdict.MISMATCH, report.verdict());
        assertEquals(List.of(Outcome.MISMATCH, Outcome.NOT_RECORDED), report.steps().stream().map(RunInputReplay.Compared::outcome).toList());
    }

    @DisplayName("배치 이력은 성공인데 그 실행의 전달 기록이 없는 워크로드만 누락으로 센다")
    @Test
    void only_successful_decisions_without_a_dispatch_record_are_missing() throws Exception {
        SchedulingHistory failed = new SchedulingHistory(3L, "w3", null, null, 0, "자원 부족", "weighted", "default");
        failed.linkToRun(1L, false);
        List<SchedulingHistory> history = List.of(recorded(1L, "n1", 0.5), recorded(2L, "n1", 0.4), failed);

        Set<Long> missing = RunInputReplay.missingDispatch(history, List.of(dispatch(1L, DispatchStatus.ACCEPTED)));

        assertEquals(Set.of(2L), missing);
    }

    @DisplayName("배치 이력과 같은 결정 수는 건너뛴 워크로드와 이력 행이 없는 워크로드를 세지 않는다")
    @Test
    void same_as_recorded_ignores_skipped_and_unrecorded_steps() {
        SchedulingHistory skip = new SchedulingHistory(2L, "w2", null, null, 0, "배치 건너뜀", "weighted", "default");
        skip.linkToRun(1L, false);
        List<SchedulingHistory> history = List.of(recorded(1L, "n1", 0.5), skip);
        List<RunInputReplay.Step> steps = List.of(replayed(1L, "n1", 0.9), new RunInputReplay.Step(2L, true, null), replayed(3L, "n1", 0.4));

        assertEquals(1, RunInputReplay.sameAsRecorded(steps, history), "1번만 센다 — 2번은 건너뜀, 3번은 이력 행 없음 · 노드 배치 스코어는 보지 않는다");
    }

    @DisplayName("배치 이력과 같은 결정 수는 실패와 실패를 같다고 세고, 노드나 성공 여부가 다르면 세지 않는다")
    @Test
    void same_as_recorded_counts_failure_against_failure() {
        SchedulingHistory failed = new SchedulingHistory(1L, "w1", null, null, 0, "자원 부족", "weighted", "default");
        failed.setCandidateCount(2);
        failed.linkToRun(1L, false);
        List<SchedulingHistory> history = List.of(failed, recorded(2L, "n1", 0.5), recorded(3L, "n1", 0.5));
        List<RunInputReplay.Step> steps = List.of(
                new RunInputReplay.Step(1L, false, SchedulingResult.failure("자원 부족")),
                replayed(2L, "n2", 0.5),
                new RunInputReplay.Step(3L, false, SchedulingResult.failure("자원 부족")));

        assertEquals(1, RunInputReplay.sameAsRecorded(steps, history), "1번(실패=실패)만 같다 — 2번은 노드, 3번은 성공 여부가 다르다");
    }

    @DisplayName("저장한 설정 노브를 새 설정에 덮어쓴다 — 지금 코드에 없는 노브는 무시하고, 형식이 다르면 읽기 예외를 던진다")
    @Test
    void stored_knobs_override_new_settings() throws Exception {
        RunInputCapture.RunInputs in;
        try (var s = getClass().getResourceAsStream("/scheduler/replay/run-input-format-1.json")) {
            in = RunInputCapture.read(new String(s.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        double defaultIdle = new SchedulerProperties().getPower().getIdleRatio();

        SchedulerProperties applied = RunInputReplay.properties(withPowerKnobs(in, "{\"idleRatio\": 0.25, \"retiredKnob\": 7}"));

        assertEquals(0.25, applied.getPower().getIdleRatio(), "저장한 값이 들어간다");
        assertNotEquals(0.25, defaultIdle, "기본값과 달라야 덮어쓴 것을 볼 수 있다");
        assertThrows(JacksonException.class, () -> RunInputReplay.properties(withPowerKnobs(in, "{\"idleRatio\": {\"v\": 1}}")),
                "형식이 다른 노브는 읽기 예외 — 실행 재생은 이것을 409 로 돌려준다");
    }

    private static RunInputCapture.RunInputs withPowerKnobs(RunInputCapture.RunInputs in, String power) {
        RunInputCapture.Knobs knobs = new RunInputCapture.Knobs(RunInputCapture.MAPPER.readTree(power),
                in.knobs().thermal(), in.knobs().network());
        return new RunInputCapture.RunInputs(in.formatVersion(), in.engineId(), in.capturedAt(), in.powerBudgetWatts(),
                in.nodes(), in.excludedNodes(), in.workloads(), in.policy(), in.mdcBudgets(), in.links(), in.cooldowns(), knobs);
    }

    private static SchedulingHistory recorded(Long workloadId, String node, double score) {
        SchedulingHistory h = new SchedulingHistory(workloadId, "w" + workloadId, "mdc-01", node, score, "최고 점수", "weighted", "default");
        h.setCandidateCount(2);
        h.linkToRun(1L, true);
        return h;
    }

    private static RunInputReplay.Step replayed(Long workloadId, String node, double score) {
        return new RunInputReplay.Step(workloadId, false, new SchedulingResult(true, "mdc-01", node, score, "최고 점수", List.of()));
    }

    /** 상태만 다른 전달 기록 — 엔티티 생성자는 워크로드를 받아 상태를 REQUESTED 로 고정하므로 필드를 직접 채운다. */
    private static PlacementDispatch dispatch(Long workloadId, DispatchStatus status) throws Exception {
        Constructor<PlacementDispatch> c = PlacementDispatch.class.getDeclaredConstructor();
        c.setAccessible(true);
        PlacementDispatch d = c.newInstance();
        ReflectionTestUtils.setField(d, "workloadId", workloadId);
        ReflectionTestUtils.setField(d, "nodeId", "n1");
        ReflectionTestUtils.setField(d, "status", status);
        return d;
    }
}

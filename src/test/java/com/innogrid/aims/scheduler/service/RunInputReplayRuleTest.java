package com.innogrid.aims.scheduler.service;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.innogrid.aims.scheduler.dto.ScheduleRunReplayResponse.Outcome;
import com.innogrid.aims.scheduler.dto.ScheduleRunReplayResponse.Verdict;
import com.innogrid.aims.scheduler.entity.PlacementDispatch;
import com.innogrid.aims.scheduler.entity.PlacementDispatch.DispatchStatus;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.model.SchedulingResult;

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

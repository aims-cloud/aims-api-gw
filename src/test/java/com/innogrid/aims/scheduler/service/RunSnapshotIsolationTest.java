package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.innogrid.aims.scheduler.entity.Schedule;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 클러스터 스냅샷이 <b>배치를 망가뜨리지 않는지</b> 본다.
 *
 * <p>스냅샷은 곁들이는 기록이지 배치의 일부가 아니다. 그런데 실행 경로 한가운데에 끼어 있어서, 계산이
 * 예외를 밖으로 내보내면 <b>잘 끝난 배치가 통째로 실패로 뒤바뀐다</b> — 워크로드는 노드를 잡았는데 이력은
 * 실패로 남아, 다음 실행이 같은 워크로드를 다시 집는다. 그래서 계산기를 던지는 대역으로 갈아끼워
 * 실행 결과가 흔들리지 않는지 값으로 못 박는다.</p>
 *
 * <p>계산기를 스프링 빈으로 둔 까닭이 바로 이 테스트다. 정적 유틸이었다면 던지게 만들 방법이 없다.</p>
 */
@SpringBootTest
class RunSnapshotIsolationTest {

    private static final String PREFIX = "rsi-";

    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired WorkloadRepository workloadRepository;

    @MockitoBean NodeResourceCollector collector;
    @MockitoBean PlacementService placementService;
    @MockitoBean RunClusterSnapshot clusterSnapshot;

    @AfterEach
    void clean() {
        runRepository.findAll().stream()
                .filter(r -> r.getScheduleKey() != null && r.getScheduleKey().startsWith(PREFIX))
                .forEach(runRepository::delete);
        workloadRepository.findAll().stream()
                .filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream()
                .filter(s -> s.getScheduleId().startsWith(PREFIX)).forEach(scheduleRepository::delete);
    }

    @DisplayName("실행 전 스냅샷이 터져도 배치는 성공으로 끝나고 기록만 실패로 남는다")
    @Test
    void a_failure_before_the_run_does_not_change_the_run_result() {
        givenOneNodeAndOneWorkload("rsi-before");
        when(clusterSnapshot.fixedOf(any(PlacementContext.class)))
                .thenThrow(new IllegalStateException("스냅샷 계산 실패"));

        ScheduleRun run = runNow("rsi-before");

        assertEquals(ScheduleRun.RunStatus.COMPLETED, run.getStatus(),
                "스냅샷 때문에 배치가 실패로 뒤바뀌었다 — 워크로드는 잡혔는데 이력만 실패가 된다");
        assertEquals(1, run.getSuccessCount());
        assertEquals(ScheduleRun.SnapshotStatus.FAILED, run.getSnapshotStatus());
        assertNull(run.getSnapshotNodeCount(), "재다 터진 회차는 빈 칸으로 남는다");
    }

    @DisplayName("실행 후 스냅샷이 터지면 앞서 적은 전 값까지 지우고 실패로 남는다")
    @Test
    void a_failure_after_the_run_clears_the_half_written_snapshot() {
        givenOneNodeAndOneWorkload("rsi-after");
        when(clusterSnapshot.fixedOf(any(PlacementContext.class)))
                .thenReturn(new RunClusterSnapshot.Fixed(1, 0, 1, 500.0));
        when(clusterSnapshot.movingOf(any(PlacementContext.class), anyDouble()))
                .thenReturn(new RunClusterSnapshot.Moving(0, 120.0, 40.0))
                .thenThrow(new IllegalStateException("스냅샷 계산 실패"));

        ScheduleRun run = runNow("rsi-after");

        assertEquals(ScheduleRun.RunStatus.COMPLETED, run.getStatus());
        assertEquals(1, run.getSuccessCount());
        assertEquals(ScheduleRun.SnapshotStatus.FAILED, run.getSnapshotStatus());
        assertNull(run.getSnapshotActiveNodesBefore(),
                "전만 남으면 나중에 전·후 차를 0 으로 읽는다 — 반쪽 값은 지운다");
        assertNull(run.getSnapshotMeasuredPowerWatts());
    }

    @DisplayName("클래스를 못 읽어 계산이 터져도 배치는 성공으로 끝난다")
    @Test
    void a_linkage_error_in_the_snapshot_does_not_break_the_run() {
        givenOneNodeAndOneWorkload("rsi-linkage");
        // 배포가 돌고 있는 JVM 의 jar 를 덮어쓰면 아직 안 읽은 클래스에서 이 오류가 난다.
        // RuntimeException 만 잡으면 하필 그때 "실행은 계속한다"는 약속이 깨진다.
        when(clusterSnapshot.fixedOf(any(PlacementContext.class)))
                .thenThrow(new NoClassDefFoundError("RunClusterSnapshot$Fixed"));

        ScheduleRun run = runNow("rsi-linkage");

        assertEquals(ScheduleRun.RunStatus.COMPLETED, run.getStatus(),
                "jar 를 덮어쓴 직후의 첫 실행이 통째로 실패로 뒤바뀐다");
        assertEquals(1, run.getSuccessCount());
        assertEquals(ScheduleRun.SnapshotStatus.FAILED, run.getSnapshotStatus());
    }

    @DisplayName("스냅샷이 멀쩡하면 전·후가 모두 적힌다")
    @Test
    void a_healthy_snapshot_records_both_sides() {
        givenOneNodeAndOneWorkload("rsi-ok");
        when(clusterSnapshot.fixedOf(any(PlacementContext.class)))
                .thenReturn(new RunClusterSnapshot.Fixed(1, 0, 1, 500.0));
        when(clusterSnapshot.movingOf(any(PlacementContext.class), anyDouble()))
                .thenReturn(new RunClusterSnapshot.Moving(0, 120.0, 40.0))
                .thenReturn(new RunClusterSnapshot.Moving(1, 0.0, 44.0));

        ScheduleRun run = runNow("rsi-ok");

        assertEquals(ScheduleRun.SnapshotStatus.OK, run.getSnapshotStatus());
        assertEquals(1, run.getSnapshotNodeCount());
        assertEquals(500.0, run.getSnapshotMeasuredPowerWatts());
        assertEquals(0, run.getSnapshotActiveNodesBefore());
        assertEquals(1, run.getSnapshotActiveNodesAfter(), "배치가 노드를 깨운 것이 보인다");
        assertEquals(120.0, run.getSnapshotReclaimableWattsBefore());
        assertEquals(0.0, run.getSnapshotReclaimableWattsAfter());
    }

    @DisplayName("실행 전 스냅샷은 배치가 끝나기 전에 이미 DB 에 닿는다")
    @Test
    void the_before_snapshot_reaches_the_database_before_the_run_ends() {
        givenOneNodeAndOneWorkload("rsi-persist");
        when(clusterSnapshot.fixedOf(any(PlacementContext.class)))
                .thenReturn(new RunClusterSnapshot.Fixed(1, 0, 1, 500.0));
        when(clusterSnapshot.movingOf(any(PlacementContext.class), anyDouble()))
                .thenReturn(new RunClusterSnapshot.Moving(0, 120.0, 40.0));

        // 배치 도중에 이력을 다시 읽는다. 이 시점에 값이 없으면, 앱이 여기서 죽었을 때
        // 그 회차는 "전만 기록됨"이 아니라 아무것도 기록되지 않은 행으로 남는다 —
        // 기동 회수는 실행 이력을 마무리할 뿐 스냅샷을 채우지 못한다.
        List<ScheduleRun.SnapshotStatus> midRun = new ArrayList<>();
        when(placementService.place(any(Workload.class), any(PlacementContext.class), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    Long runId = invocation.getArgument(3);
                    midRun.add(runRepository.findById(runId).orElseThrow().getSnapshotStatus());
                    return new SchedulingResult(true, "mdc-01", "rsi-persist-node", 0.9, "테스트 배치", List.of());
                });

        runNow("rsi-persist");

        assertEquals(List.of(ScheduleRun.SnapshotStatus.BEFORE_ONLY), midRun,
                "배치 도중의 이력에 전 스냅샷이 없다 — 중단된 회차와 기능 이전 행이 구분되지 않는다");
    }

    @DisplayName("전을 못 적었으면 후만 적지 않는다")
    @Test
    void the_after_side_is_not_written_without_a_before_side() {
        givenOneNodeAndOneWorkload("rsi-guard");
        // 전이 터지고 후는 멀쩡한 경우 — DB 커넥션이 잠깐 끊겼다 돌아온 상황이 이렇다.
        when(clusterSnapshot.fixedOf(any(PlacementContext.class)))
                .thenThrow(new IllegalStateException("전 스냅샷 실패"));
        when(clusterSnapshot.movingOf(any(PlacementContext.class), anyDouble()))
                .thenReturn(new RunClusterSnapshot.Moving(1, 0.0, 44.0));

        ScheduleRun run = runNow("rsi-guard");

        assertEquals(ScheduleRun.SnapshotStatus.FAILED, run.getSnapshotStatus(),
                "후만 적히면 상태가 OK 로 뒤집혀, 짝이 없는 값으로 전·후 차를 계산하게 된다");
        assertNull(run.getSnapshotActiveNodesAfter(), "짝이 없는 후 값은 남기지 않는다");
    }

    @DisplayName("기록 실패는 열 열둘을 남김없이 비운다")
    @Test
    void a_failed_snapshot_clears_every_column() {
        ScheduleRun run = new ScheduleRun(1L, "rsi-clear", ScheduleRun.TriggerType.MANUAL, "tester");
        run.recordSnapshotBefore("0123456789ab", 3, 1, 2, 600.0, 1, 120.0, 40.0);
        run.recordSnapshotAfter(2, 60.0, 44.0);

        run.markSnapshotFailed();

        assertEquals(ScheduleRun.SnapshotStatus.FAILED, run.getSnapshotStatus());
        assertNull(run.getSnapshotKnobsDigest());
        assertNull(run.getSnapshotNodeCount());
        assertNull(run.getSnapshotExcludedCount());
        assertNull(run.getSnapshotMeasuredNodes());
        assertNull(run.getSnapshotMeasuredPowerWatts());
        assertNull(run.getSnapshotActiveNodesBefore());
        assertNull(run.getSnapshotActiveNodesAfter());
        assertNull(run.getSnapshotReclaimableWattsBefore());
        assertNull(run.getSnapshotReclaimableWattsAfter());
        assertNull(run.getSnapshotAvgTemperatureBefore());
        assertNull(run.getSnapshotAvgTemperatureAfter());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private void givenOneNodeAndOneWorkload(String key) {
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", key + "-node", 16, 65_536, 0, 20, 30, 30, 200, 500, 40, 1_000, 1_000)),
                List.of()));
        when(placementService.place(any(Workload.class), any(PlacementContext.class), anyString(), anyLong()))
                .thenReturn(new SchedulingResult(true, "mdc-01", key + "-node", 0.9, "테스트 배치", List.of()));

        Workload workload = new Workload(PREFIX + key, 1, 1024);
        workload.setResourceType(Workload.ResourceType.VM);
        workloadRepository.save(workload);
    }

    private ScheduleRun runNow(String key) {
        Schedule schedule = new Schedule(PREFIX + key, "스냅샷 격리 " + key, "0 0 2 * * ?");
        schedule.setResourceType(Workload.ResourceType.VM);
        schedule.setEnabled(true);
        schedule.setAutoRun(false);
        return executionService.runNow(scheduleRepository.save(schedule).getId(), "tester");
    }
}

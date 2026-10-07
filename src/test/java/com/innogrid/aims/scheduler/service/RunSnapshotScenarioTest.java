package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleRunDetailResponse;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.dispatch.PlacementDispatcher;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 시나리오 — 실행 1회가 클러스터를 무엇으로 바꿨는지 실행 이력에서 읽어낸다.
 *
 * <pre>
 * 노드      16코어 2대, CPU 1%(가동 문턱 5% 아래), 실측 300W / 정격 500W, 35℃
 * 워크로드  8코어 1건 — 배치되면 그 노드의 실효 CPU 가 문턱을 넘어 "깨어난다"
 * 기대      고정 축: 노드 2, 계측 2, 실측 전력 600W (예약을 얹어도 그대로)
 *           움직이는 축: 가동 0 → 1, 회수 가능 전력 줄어듦
 * </pre>
 *
 * <p>계산기를 대역으로 바꾸지 않는다 — 실제 엔진이 실제로 예약을 쌓은 뒤의 값을 본다. 대역으로는
 * "예약이 숫자를 움직인다"는 이 기능의 전제 자체를 확인할 수 없다.</p>
 */
@SpringBootTest
class RunSnapshotScenarioTest {

    private static final String PREFIX = "rss-";

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ScheduleRunRepository runRepository;
    @Autowired WorkloadRepository workloadRepository;
    @Autowired SchedulingHistoryRepository historyRepository;

    @MockitoBean NodeResourceCollector collector;
    @MockitoBean PlacementDispatcher dispatcher;

    /** 실행 계층은 기본적으로 받아들인다. 거부 시나리오만 이 대역을 덮어쓴다. */
    @BeforeEach
    void acceptByDefault() {
        when(dispatcher.getMode()).thenReturn("record");
        when(dispatcher.dispatch(any(PlacementDispatcher.Request.class)))
                .thenReturn(PlacementDispatcher.Outcome.accepted("test-ref", "테스트 수용"));
    }

    @AfterEach
    void clean() {
        historyRepository.findAll().stream()
                .filter(h -> h.getWorkloadName() != null && h.getWorkloadName().startsWith(PREFIX))
                .forEach(historyRepository::delete);
        runRepository.findAll().stream()
                .filter(r -> r.getScheduleKey() != null && r.getScheduleKey().startsWith(PREFIX))
                .forEach(runRepository::delete);
        workloadRepository.findAll().stream()
                .filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream()
                .filter(s -> s.getScheduleId().startsWith(PREFIX))
                .forEach(s -> scheduleService.delete(List.of(s.getId())));
    }

    @DisplayName("배치가 노드를 깨우면 움직이는 축만 달라지고 고정 축은 실행 전후가 같다")
    @Test
    void a_run_that_wakes_a_node_shows_it_in_the_moving_axes() {
        givenIdleNodes();
        save("w1", 8);

        ScheduleRun run = runNow("wake", Workload.ResourceType.GPU);

        assertEquals(1, run.getSuccessCount(), "배치가 되지 않으면 이 시나리오는 아무것도 보이지 않는다");
        assertEquals(ScheduleRun.SnapshotStatus.OK, run.getSnapshotStatus());

        assertEquals(2, run.getSnapshotNodeCount());
        assertEquals(0, run.getSnapshotExcludedCount());
        assertEquals(2, run.getSnapshotMeasuredNodes());
        assertEquals(600.0, run.getSnapshotMeasuredPowerWatts(), "실측 전력은 배치를 얹어도 다시 재지 않는다");

        assertEquals(0, run.getSnapshotActiveNodesBefore(), "실행 전에는 두 대 다 유휴다");
        assertEquals(1, run.getSnapshotActiveNodesAfter(), "8코어를 얹은 노드가 가동으로 넘어간다");
        assertTrue(run.getSnapshotReclaimableWattsAfter() < run.getSnapshotReclaimableWattsBefore(),
                "깨어난 노드가 회수 대상에서 빠져 회수 가능 전력이 줄어야 한다");
        assertTrue(run.getSnapshotAvgTemperatureAfter() > run.getSnapshotAvgTemperatureBefore(),
                "예약이 얹히면 예상 온도가 오른다");
    }

    @DisplayName("실행 상세 응답이 스냅샷을 그대로 싣는다")
    @Test
    void the_detail_response_carries_the_snapshot() {
        givenIdleNodes();
        save("w1", 8);

        ScheduleRun run = runNow("detail", Workload.ResourceType.GPU);
        ScheduleRunDetailResponse detail = scheduleService.runDetail(run.getScheduleId(), run.getId());

        ScheduleRunDetailResponse.ClusterSnapshot snapshot = detail.clusterSnapshot();
        assertNotNull(snapshot, "상세에 스냅샷이 실리지 않으면 화면이 읽을 길이 없다");
        assertEquals(ScheduleRun.SnapshotStatus.OK, snapshot.status());
        assertEquals(run.getSnapshotNodeCount(), snapshot.nodeCount());
        assertEquals(run.getSnapshotKnobsDigest(), snapshot.knobsDigest());
        assertEquals(run.getSnapshotActiveNodesAfter(), snapshot.activeNodesAfter());
        assertEquals(run.getSnapshotMeasuredPowerWatts(), snapshot.measuredPowerWatts());
    }

    @DisplayName("기록이 없는 실행은 상세에서 스냅샷이 통째로 비어 있다")
    @Test
    void a_run_without_a_snapshot_reports_nothing_rather_than_zeros() {
        // 이 열이 생기기 전에 쌓인 행 — 상태부터 비어 있다.
        ScheduleRun legacy = new ScheduleRun(1L, PREFIX + "legacy", ScheduleRun.TriggerType.AUTO, "SYSTEM");

        assertNull(ScheduleRunDetailResponse.ClusterSnapshot.from(legacy),
                "옛 실행까지 0 으로 채워 내리면 없던 회수 가능 전력이 생긴다");
    }

    @DisplayName("대상이 없어 끝난 실행은 평범한 회차로 갈려 남는다")
    @Test
    void a_run_with_no_target_is_marked_as_an_ordinary_run() {
        givenIdleNodes();

        ScheduleRun run = runNow("no-target", Workload.ResourceType.NPU);

        assertEquals(0, run.getTargetCount());
        assertEquals(ScheduleRun.SnapshotStatus.NO_TARGET, run.getSnapshotStatus(),
                "장애로 못 잰 회차와 같은 값을 달면 어느 쪽이 쌓이는지 알 수 없다");
        assertNull(run.getSnapshotNodeCount(), "재지 않은 칸은 비어 있다");
    }

    @DisplayName("노드를 한 대도 못 모은 실행은 장애 회차로 갈려 남는다")
    @Test
    void a_run_without_any_node_is_marked_as_a_failure_to_collect() {
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(List.of(), List.of()));
        save("w1", 8);

        ScheduleRun run = runNow("no-node", Workload.ResourceType.GPU);

        assertEquals(ScheduleRun.RunStatus.FAILED, run.getStatus());
        assertEquals(ScheduleRun.SnapshotStatus.NO_NODES, run.getSnapshotStatus());
        assertNull(run.getSnapshotMeasuredPowerWatts());
    }

    @DisplayName("실행 계층이 배치를 되돌리면 그 예약도 되돌아가 후 값이 전 값과 같다")
    @Test
    void the_after_side_drops_reservations_when_the_dispatch_layer_rejects() {
        givenIdleNodes();
        save("w1", 8);
        // 실행 계층이 거부하면 워크로드가 되돌아가고, 실행 루프가 그 배치의 예약도 같은 맥락에서 되돌린다.
        // 그래서 전부 무산된 실행의 후 값은 전 값과 같다(계약 문서 2장 실행 전후 클러스터 상태 설명).
        when(dispatcher.dispatch(any(PlacementDispatcher.Request.class)))
                .thenReturn(PlacementDispatcher.Outcome.rejected("실행 계층이 거부"));

        ScheduleRun run = runNow("rejected", Workload.ResourceType.GPU);

        assertEquals(0, run.getSuccessCount(), "거부되면 성공으로 세지 않는다");
        assertEquals(ScheduleRun.SnapshotStatus.OK, run.getSnapshotStatus());
        assertEquals(0, run.getSnapshotActiveNodesBefore());
        assertEquals(0, run.getSnapshotActiveNodesAfter(),
                "되돌려진 배치의 예약은 후 값에 남지 않는다");
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** 16코어 2대, CPU 1% — 가동 문턱(5%) 아래라 둘 다 유휴로 센다. */
    private void givenIdleNodes() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(List.of(
                new NodeResource("mdc-01", PREFIX + "node-1", 16, 65_536, 0, 1, 5, 10, 300, 500, 35, 1_000, 1_000),
                new NodeResource("mdc-01", PREFIX + "node-2", 16, 65_536, 0, 1, 5, 10, 300, 500, 35, 1_000, 1_000)),
                List.of()));
    }

    private void save(String name, int cpu) {
        Workload workload = new Workload(PREFIX + name, cpu, 4_096);
        workload.setResourceType(Workload.ResourceType.GPU);
        workloadRepository.save(workload);
    }

    private ScheduleRun runNow(String key, Workload.ResourceType type) {
        long scheduleId = scheduleService.create(new ScheduleRequest(
                PREFIX + key, PREFIX + key, type, "0 0 2 * * ?",
                false, true, PREFIX, "weighted", null, null, null, null)).id();
        return executionService.runNow(scheduleId, "tester");
    }
}

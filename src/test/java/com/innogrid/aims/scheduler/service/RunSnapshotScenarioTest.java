package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.dto.ScheduleRunDetailResponse;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.ScheduleRunRepository;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
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

    @DisplayName("대상이 없어 끝난 실행은 잴 판이 없었다고 남는다")
    @Test
    void a_run_with_no_target_records_a_skipped_snapshot() {
        givenIdleNodes();

        ScheduleRun run = runNow("no-target", Workload.ResourceType.NPU);

        assertEquals(0, run.getTargetCount());
        assertEquals(ScheduleRun.SnapshotStatus.SKIPPED, run.getSnapshotStatus());
        assertNull(run.getSnapshotNodeCount(), "재지 않은 칸은 비어 있다");
    }

    @DisplayName("활성 노드가 하나도 없어 실패한 실행도 잴 판이 없었다고 남는다")
    @Test
    void a_run_without_any_node_records_a_skipped_snapshot() {
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(List.of(), List.of()));
        save("w1", 8);

        ScheduleRun run = runNow("no-node", Workload.ResourceType.GPU);

        assertEquals(ScheduleRun.RunStatus.FAILED, run.getStatus());
        assertEquals(ScheduleRun.SnapshotStatus.SKIPPED, run.getSnapshotStatus());
        assertNull(run.getSnapshotMeasuredPowerWatts());
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

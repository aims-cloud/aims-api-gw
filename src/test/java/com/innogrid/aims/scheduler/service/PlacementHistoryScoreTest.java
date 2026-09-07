package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 배치 결정 이력에 <b>선택된 노드</b>의 차원별 점수가 남는가.
 *
 * <p><b>무엇이 문제였나</b> — 엔진이 돌려주는 후보 점수 목록은 <b>제외된 노드까지 포함해</b> 총점순으로 정렬돼
 * 있는데(엔진 3종 공통), 승자는 제외되지 않은 노드 중에서 고른다. 그래서 점수를 깎지 않는 제약(GPU·디스크·
 * 링크 DOWN·MDC 예산)에 걸린 노드가 1위이면 <b>목록의 첫 항목이 승자가 아니다.</b> 이력은 그 첫 항목을 저장했다.</p>
 *
 * <p>그 결과 한 행 안에서 총점·사유·노드 상태는 승자 것인데 <b>차원별 점수만 다른 노드 것</b>이었다.
 * 이 표는 연구 근거라 지워지지 않으므로, 틀린 채로 쌓이면 되돌릴 수 없다.</p>
 *
 * <p>스프링을 띄우지 않는다 — 저장소 둘은 대역으로 두고 엔진은 직접 만든다. 확인 대상이 응답이 아니라
 * <b>저장된 행</b>이므로 {@code save} 에 들어간 인자를 잡아서 본다.</p>
 */
class PlacementHistoryScoreTest {

    /** 기본 정책의 가중치 — 아래 불변식 단언에 쓴다. */
    private static final double CPU_W = 0.25, MEM_W = 0.25, PWR_W = 0.20, TEMP_W = 0.15, NET_W = 0.15;

    private final WorkloadRepository workloadRepository = mock(WorkloadRepository.class);
    private final SchedulingHistoryRepository historyRepository = mock(SchedulingHistoryRepository.class);
    private final PlacementService placementService = new PlacementService(
            workloadRepository, historyRepository,
            new EngineRegistry(List.of(new WeightedScoreScheduler())));

    /**
     * 제외된 노드가 총점 1위인 상황을 결정적으로 만든다.
     *
     * <p>{@code nogpu} 는 GPU 가 없어 제외되지만 CPU·메모리가 거의 비어 총점이 가장 높고, {@code gpu} 는
     * GPU 가 있어 적격이지만 사용률이 높아 총점이 낮다. 전력·온도·트래픽을 0 으로 두어 그 세 항이
     * 두 노드에서 같은 값이 되게 했다 — 차이가 CPU·메모리에서만 나므로 결과가 흔들리지 않는다.</p>
     */
    private PlacementContext context() {
        NodeResource excludedButTop = new NodeResource("mdc-01", "nogpu",
                32, 131072, 0, 1, 1, 0, 0, 0, 0, 0, 0);
        NodeResource selected = new NodeResource("mdc-01", "gpu",
                32, 131072, 4, 60, 60, 0, 0, 0, 0, 0, 0);
        return PlacementContext.of(List.of(excludedButTop, selected), new SchedulingPolicy("기본", "테스트용"));
    }

    private Workload gpuWorkload() {
        Workload workload = new Workload("gpu-w", 1, 1024);
        workload.setRequiredGpu(1);
        return workload;
    }

    private SchedulingHistory captureSaved() {
        ArgumentCaptor<SchedulingHistory> captor = ArgumentCaptor.forClass(SchedulingHistory.class);
        verify(historyRepository).save(captor.capture());
        return captor.getValue();
    }

    @DisplayName("제외된 노드가 총점 1위여도 이력에는 선택된 노드의 점수가 남는다")
    @Test
    void history_keeps_the_selected_node_scores() {
        placementService.place(gpuWorkload(), context(), "weighted");

        SchedulingHistory saved = captureSaved();
        assertEquals("gpu", saved.getSelectedNodeId(), "이 시나리오는 GPU 가 있는 노드가 뽑혀야 성립한다");

        // 저장된 다섯 점수가 모두 선택된 노드에서 왔는가 — 한 행 안의 불변식으로 본다.
        // 총점은 승자 것이므로, 다섯 중 하나라도 다른 노드에서 오면 가중합이 총점과 어긋난다.
        // (우선순위 NORMAL·선호 MDC 없음이라 배수가 1.0 이고, 엔진이 반올림하므로 오차를 둔다)
        double weighted = saved.getCpuScore() * CPU_W + saved.getMemoryScore() * MEM_W
                + saved.getPowerScore() * PWR_W + saved.getTemperatureScore() * TEMP_W
                + saved.getNetworkScore() * NET_W;
        assertEquals(saved.getFinalScore(), weighted, 1e-3,
                "차원별 점수의 가중합이 총점과 다르다 — 둘이 서로 다른 노드에서 왔다는 뜻이다");
    }

    @DisplayName("선택된 노드의 점수와 사용률이 같은 노드에서 온다")
    @Test
    void scores_and_usage_come_from_the_same_node() {
        placementService.place(gpuWorkload(), context(), "weighted");

        SchedulingHistory saved = captureSaved();
        // 예약이 없는 첫 배치라 실효 사용률 = 실측 사용률이고, CPU 점수는 (100 - 사용률)/100 이다.
        assertEquals((100 - saved.getNodeCpuUsage()) / 100.0, saved.getCpuScore(), 1e-3,
                "CPU 점수와 CPU 사용률이 다른 노드에서 왔다");
        assertEquals((100 - saved.getNodeMemoryUsage()) / 100.0, saved.getMemoryScore(), 1e-3,
                "메모리 점수와 메모리 사용률이 다른 노드에서 왔다");
    }

    /**
     * 배치에 실패하면 선택된 노드가 없다. 그때 제외 노드의 점수를 남기면 <b>그 숫자가 어느 노드 것인지
     * 적을 자리가 없는</b> 행이 영구히 남는다. 총점과 노드 상태가 이미 0 이므로 점수도 0 으로 둔다.
     */
    @DisplayName("배치에 실패하면 차원별 점수를 0 으로 남긴다 — 제외 노드 점수를 넣지 않는다")
    @Test
    void failed_placement_leaves_zero_scores() {
        Workload tooBig = new Workload("too-big", 999, 99_999_999);
        tooBig.setRequiredGpu(99);

        placementService.place(tooBig, context(), "weighted", 42L);

        SchedulingHistory saved = captureSaved();
        assertNull(saved.getSelectedNodeId(), "이 시나리오는 배치가 실패해야 성립한다");
        assertEquals(0.0, saved.getCpuScore());
        assertEquals(0.0, saved.getMemoryScore());
        assertEquals(0.0, saved.getPowerScore());
        assertEquals(0.0, saved.getTemperatureScore());
        assertEquals(0.0, saved.getNetworkScore());
        assertEquals(0.0, saved.getFinalScore(), "총점도 0 이라 행 전체가 일관된다");
    }
}

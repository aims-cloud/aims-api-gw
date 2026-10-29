package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.repository.PlacementInputRepository;
import com.innogrid.aims.scheduler.repository.SchedulingPolicyRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 단건 배치 입력 저장이 실패해도 배치 결과와 실행 계층 전달은 그대로인가 — 저장소가 예외를 던지도록 조립한다(스프링 컨텍스트 없음).
 *
 * <p>저장은 {@link PlacementService#place} 의 트랜잭션이 끝난 뒤라 결정은 이미 커밋되어 있다. 여기서 예외가 새면 워크로드는
 * 배치됐는데 실행 계층 전달 없이 500 이 나간다.</p>
 */
class PlacementInputSaveFailureTest {

    private final WorkloadRepository workloadRepository = mock(WorkloadRepository.class);
    private final SchedulingPolicyRepository policyRepository = mock(SchedulingPolicyRepository.class);
    private final NodeResourceCollector collector = mock(NodeResourceCollector.class);
    private final PlacementService placement = mock(PlacementService.class);
    private final PlacementDispatchService dispatch = mock(PlacementDispatchService.class);
    private final PlacementInputRepository inputs = mock(PlacementInputRepository.class);

    @DisplayName("입력 저장소가 예외를 던져도 배치 결과는 그대로 돌아오고 실행 계층 전달도 한다")
    @Test
    void save_failure_keeps_result_and_dispatch() {
        Workload workload = new Workload("w", 1, 512);
        when(workloadRepository.findById(anyLong())).thenReturn(Optional.of(workload));
        when(workloadRepository.findByStatusIn(anyCollection())).thenReturn(List.of());
        when(policyRepository.findFirstByActiveTrueAndResourceTypeIsNull())
                .thenReturn(Optional.of(new SchedulingPolicy("default", "기본")));
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", "n1:9100", 8, 16384, 0, 30, 40, 30, 200, 500, 45, 1000, 500)), List.of()));
        when(placement.place(any(Workload.class), any(PlacementContext.class), anyString()))
                .thenReturn(new SchedulingResult(true, "mdc-01", "n1:9100", 0.7, "ok", List.of()));
        when(inputs.save(any())).thenThrow(new IllegalStateException("저장 실패"));
        SchedulingService service = SchedulingServiceBuilder.aSchedulingService()
                .workloads(workloadRepository).policies(policyRepository).collector(collector).placement(placement)
                .engines(new EngineRegistry(List.of(new WeightedScoreScheduler())))
                .dispatch(dispatch)
                .placementInputs(inputs, new ThermalModel(new SchedulerProperties()))
                .build();

        SchedulingResult result = service.scheduleWorkload(1L, "weighted");

        assertTrue(result.success());
        assertEquals("n1:9100", result.selectedNodeId());
        verify(inputs).save(any());   // 저장을 실제로 시도했다 — 시도하지 않고 통과하는 테스트가 되지 않게
        verify(dispatch).afterPlacement(eq(workload), any(), isNull());
    }
}

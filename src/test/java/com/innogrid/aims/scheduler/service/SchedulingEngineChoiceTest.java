package com.innogrid.aims.scheduler.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.SchedulingEngine;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.engine.model.ThermalModel;
import com.innogrid.aims.scheduler.entity.PlacementInput;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 단건 배치·모의 배치의 엔진 선택 — 요청한 엔진, 비었으면 적용 정책의 기본 엔진(등록된 것일 때만), 그것도 없으면 weighted.
 * 스케줄 실행과 같은 규칙이다(스프링 컨텍스트 없음 · 활성 정책을 DB 에 만들지 않는다).
 *
 * <p>엔진은 자기 id 를 사유에 싣는 대역이라, 모의 배치 결과의 사유로 어느 엔진이 돌았는지 본다. 실제 배치는 배치 서비스 대역에
 * 넘긴 엔진 id 와 입력 행에 남은 엔진 id 로 본다.</p>
 */
class SchedulingEngineChoiceTest {

    private final WorkloadRepository workloadRepository = mock(WorkloadRepository.class);
    private final SchedulingPolicyRepository policyRepository = mock(SchedulingPolicyRepository.class);
    private final NodeResourceCollector collector = mock(NodeResourceCollector.class);
    private final PlacementService placement = mock(PlacementService.class);
    private final PlacementInputRepository inputs = mock(PlacementInputRepository.class);
    private final Workload workload = new Workload("w", 1, 512);

    /** 정책 기본 엔진을 {@code defaultEngine} 으로 둔 공통 활성 정책 하나와 노드 하나로 조립한다. */
    private SchedulingService serviceWithPolicyEngine(String defaultEngine) {
        SchedulingPolicy policy = new SchedulingPolicy("engine-choice", "엔진 선택");
        policy.setDefaultEngine(defaultEngine);
        when(workloadRepository.findById(anyLong())).thenReturn(Optional.of(workload));
        when(workloadRepository.findByStatusIn(anyCollection())).thenReturn(List.of());
        when(policyRepository.findFirstByActiveTrueAndResourceTypeIsNull()).thenReturn(Optional.of(policy));
        when(collector.collect()).thenReturn(new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", "n1:9100", 8, 16384, 0, 30, 40, 30, 200, 500, 45, 1000, 500)), List.of()));
        when(placement.place(any(Workload.class), any(PlacementContext.class), anyString()))
                .thenReturn(new SchedulingResult(true, "mdc-01", "n1:9100", 0.7, "ok", List.of()));
        return SchedulingServiceBuilder.aSchedulingService()
                .workloads(workloadRepository).policies(policyRepository).collector(collector).placement(placement)
                .engines(new EngineRegistry(List.of(engine("weighted"), engine("power"), engine("temperature"))))
                .placementInputs(inputs, new ThermalModel(new SchedulerProperties()))
                .build();
    }

    /** 자기 id 를 사유에 싣는 엔진 대역. */
    private static SchedulingEngine engine(String id) {
        return new SchedulingEngine() {
            @Override public SchedulingResult schedule(Workload w, PlacementContext context) {
                return new SchedulingResult(true, "mdc-01", "n1:9100", 0.5, id, List.of());
            }
            @Override public String getName() { return id; }
            @Override public String getId() { return id; }
        };
    }

    /** 입력 행에 남은 엔진 id. */
    private String savedInputEngine() {
        ArgumentCaptor<PlacementInput> saved = ArgumentCaptor.forClass(PlacementInput.class);
        verify(inputs).save(saved.capture());
        return saved.getValue().getEngineId();
    }

    @DisplayName("엔진을 비운 단건 배치는 정책 기본 엔진으로 배치하고 입력 행에도 그 엔진을 남긴다")
    @Test
    void omitted_engine_uses_policy_default_engine() {
        SchedulingService service = serviceWithPolicyEngine("power");

        service.scheduleWorkload(1L, null);

        verify(placement).place(eq(workload), any(PlacementContext.class), eq("power"));
        assertEquals("power", savedInputEngine());
    }

    @DisplayName("빈 문자열·공백 엔진은 비운 것과 같다 — 400 이 아니라 정책 기본 엔진")
    @Test
    void blank_engine_is_same_as_omitted() {
        SchedulingService service = serviceWithPolicyEngine("power");

        assertEquals("power", service.dryRun(1L, "").reason());
        assertEquals("power", service.dryRun(1L, "  ").reason());
    }

    @DisplayName("요청한 엔진이 정책 기본 엔진보다 앞선다")
    @Test
    void requested_engine_wins_over_policy() {
        SchedulingService service = serviceWithPolicyEngine("power");

        service.scheduleWorkload(1L, "temperature");

        verify(placement).place(eq(workload), any(PlacementContext.class), eq("temperature"));
        assertEquals("temperature", savedInputEngine());
    }

    @DisplayName("정책에 기본 엔진이 없으면 weighted 로 배치한다")
    @Test
    void no_policy_engine_falls_back_to_weighted() {
        SchedulingService service = serviceWithPolicyEngine(null);

        service.scheduleWorkload(1L, null);

        verify(placement).place(eq(workload), any(PlacementContext.class), eq("weighted"));
    }

    @DisplayName("정책 기본 엔진이 등록되지 않은 이름이면 weighted 로 배치한다 — 400 이 아니다")
    @Test
    void unregistered_policy_engine_falls_back_to_weighted() {
        SchedulingService service = serviceWithPolicyEngine("legacy");

        service.scheduleWorkload(1L, null);

        verify(placement).place(eq(workload), any(PlacementContext.class), eq("weighted"));
    }

    @DisplayName("저장된 워크로드 모의 배치와 저장 전 모의 배치도 엔진을 비우면 정책 기본 엔진으로 평가한다")
    @Test
    void dry_runs_use_policy_default_engine() {
        SchedulingService service = serviceWithPolicyEngine("power");

        assertEquals("power", service.dryRun(1L, null).reason());
        assertEquals("power", service.dryRun(new Workload("draft", 1, 512), null).reason());
        assertEquals("temperature", service.dryRun(1L, "temperature").reason(), "요청한 엔진이 앞선다");
    }
}

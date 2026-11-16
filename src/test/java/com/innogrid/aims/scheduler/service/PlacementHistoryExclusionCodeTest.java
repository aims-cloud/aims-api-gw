package com.innogrid.aims.scheduler.service;

import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.innogrid.aims.scheduler.engine.EngineRegistry;
import com.innogrid.aims.scheduler.engine.PowerAwareScheduler;
import com.innogrid.aims.scheduler.engine.TemperatureAwareScheduler;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.entity.SchedulingHistory;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;
import com.innogrid.aims.scheduler.repository.SchedulingHistoryRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 배치 이력의 제외 사유 코드 집계 — 엔진이 돌려주는 후보별 제외 코드가 이력에 코드별 건수로 남는지.
 *
 * <p>스프링을 띄우지 않는다 — 저장소 둘은 대역으로 두고 엔진은 직접 만든다. 확인 대상은 응답이 아니라 <b>저장된 행</b>이므로
 * {@code save} 에 들어간 인자를 잡아서 본다({@code PlacementHistoryScoreTest} 와 같은 골격).</p>
 */
class PlacementHistoryExclusionCodeTest {

    private final WorkloadRepository workloadRepository = mock(WorkloadRepository.class);
    private final SchedulingHistoryRepository historyRepository = mock(SchedulingHistoryRepository.class);
    private final PlacementService placementService = new PlacementService(
            workloadRepository, historyRepository,
            new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(), new TemperatureAwareScheduler())));

    /** GPU 없는 노드 둘 + GPU 있는 노드 하나 — GPU 요구 워크로드에서 둘이 같은 코드로 빠진다. */
    private PlacementContext context() {
        NodeResource noGpuA = new NodeResource("mdc-01", "nogpu-a", 32, 131072, 0, 1, 1, 0, 0, 0, 0, 0, 0);
        NodeResource noGpuB = new NodeResource("mdc-01", "nogpu-b", 32, 131072, 0, 2, 2, 0, 0, 0, 0, 0, 0);
        NodeResource gpu = new NodeResource("mdc-01", "gpu", 32, 131072, 4, 60, 60, 0, 0, 0, 0, 0, 0);
        return PlacementContext.of(List.of(noGpuA, noGpuB, gpu), new SchedulingPolicy("기본", "테스트용"));
    }

    private SchedulingHistory captureSaved() {
        ArgumentCaptor<SchedulingHistory> captor = ArgumentCaptor.forClass(SchedulingHistory.class);
        verify(historyRepository).save(captor.capture());
        return captor.getValue();
    }

    /** 운영 모양대로 — 문장은 한국어, 코드는 이름, details 는 가변 맵(엔진 후처리 규약). */
    private static NodeScore score(String node, ExclusionReason code) {
        return new NodeScore("mdc-01", node, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, code == null,
                code == null ? null : "시험용 제외 사유 " + code.name().toLowerCase(), code, new LinkedHashMap<>());
    }

    /** GPU 없는 노드(자원 부족) + GPU 는 있으나 CPU 95%(기본 정책 상한 80% 초과) + 적격 노드 — 두 종류의 코드가 한 결정에 나온다. */
    private PlacementContext twoCodeContext() {
        NodeResource noGpu = new NodeResource("mdc-01", "nogpu", 32, 131072, 0, 1, 1, 0, 0, 0, 0, 0, 0);
        NodeResource hotCpu = new NodeResource("mdc-01", "hotcpu", 32, 131072, 4, 95, 10, 0, 0, 0, 0, 0, 0);
        NodeResource gpu = new NodeResource("mdc-01", "gpu", 32, 131072, 4, 60, 60, 0, 0, 0, 0, 0, 0);
        return PlacementContext.of(List.of(noGpu, hotCpu, gpu), new SchedulingPolicy("기본", "테스트용"));
    }

    @DisplayName("성공한 배치에서도 나머지 후보가 빠진 사유가 코드별 건수로 남고 승자는 세지 않는다")
    @Test
    void success_row_keeps_exclusion_codes_of_the_other_candidates() {
        Workload workload = new Workload("gpu-w", 1, 1024);
        workload.setRequiredGpu(1);

        placementService.place(workload, context(), "weighted");

        SchedulingHistory saved = captureSaved();
        assertEquals("gpu", saved.getSelectedNodeId(), "이 시나리오는 GPU 노드가 뽑혀야 성립한다");
        assertEquals(Boolean.TRUE, saved.getSuccess());
        assertEquals("{\"INSUFFICIENT_RESOURCE\":2}", saved.getExclusionCodes(), "GPU 없는 두 노드만 세고 승자는 세지 않는다");
    }

    @DisplayName("실패한 배치는 후보 전원의 제외 코드가 건수로 남는다")
    @Test
    void failed_row_counts_every_excluded_candidate() {
        Workload tooBig = new Workload("too-big", 1, 1024);
        tooBig.setRequiredGpu(99);

        placementService.place(tooBig, context(), "weighted", 42L);

        SchedulingHistory saved = captureSaved();
        assertNull(saved.getSelectedNodeId(), "이 시나리오는 배치가 실패해야 성립한다");
        assertEquals(Boolean.FALSE, saved.getSuccess());
        assertEquals("{\"INSUFFICIENT_RESOURCE\":3}", saved.getExclusionCodes());
    }

    @DisplayName("제외가 하나도 없으면 빈 객체가 아니라 null 이다 — 옛 행과 구분하지 않는다")
    @Test
    void no_exclusion_leaves_null() {
        Workload small = new Workload("small", 1, 1024);

        placementService.place(small, context(), "weighted");

        SchedulingHistory saved = captureSaved();
        assertEquals(Boolean.TRUE, saved.getSuccess());
        assertNull(saved.getExclusionCodes(), "세 노드 모두 적격이라 셀 것이 없다");
    }

    @DisplayName("엔진 3종 모두 같은 결정에서 서로 다른 두 코드를 건수로 남기고 승자는 세지 않는다 — 먼저 걸린 제약 하나만")
    @Test
    void every_engine_records_two_distinct_codes() {
        for (String engine : List.of("weighted", "power", "temperature")) {
            SchedulingHistoryRepository repo = mock(SchedulingHistoryRepository.class);
            PlacementService service = new PlacementService(workloadRepository, repo,
                    new EngineRegistry(List.of(new WeightedScoreScheduler(), new PowerAwareScheduler(), new TemperatureAwareScheduler())));
            Workload workload = new Workload("gpu-w-" + engine, 1, 1024);
            workload.setRequiredGpu(1);

            service.place(workload, twoCodeContext(), engine);

            ArgumentCaptor<SchedulingHistory> captor = ArgumentCaptor.forClass(SchedulingHistory.class);
            verify(repo).save(captor.capture());
            SchedulingHistory saved = captor.getValue();
            assertEquals("gpu", saved.getSelectedNodeId(), engine + ": 적격 노드가 뽑혀야 성립한다");
            assertEquals("{\"CPU_USAGE_EXCEEDED\":1,\"INSUFFICIENT_RESOURCE\":1}", saved.getExclusionCodes(),
                    engine + ": 자원 부족 1 · CPU 초과 1, 합 = 부적격 후보 수 2 = candidateCount 3 − 적격 1");
            assertEquals(3, saved.getCandidateCount());
        }
    }

    @DisplayName("제외 사유 문장만 있고 코드가 없는 후보는 만들 수 없다 — 집계에서 조용히 빠지는 길을 막는다")
    @Test
    void reason_without_code_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new NodeScore("mdc-01", "n1", 0, 0, 0, 0, 0, 0, false, "자원 부족"));
        assertDoesNotThrow(() -> new NodeScore("mdc-01", "n1", 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, true, null), "적격 후보는 그대로");
    }

    @DisplayName("집계 문자열은 코드 이름 오름차순이라 같은 결정은 같은 문자열이 되고, 후보가 없으면 null 이다")
    @Test
    void aggregate_is_sorted_and_deterministic() {
        SchedulingResult result = new SchedulingResult(false, null, null, 0, "전부 제외", List.of(
                score("n1", ExclusionReason.TEMPERATURE_EXCEEDED),
                score("n2", ExclusionReason.COOLING_DOWN),
                score("n3", ExclusionReason.INSUFFICIENT_RESOURCE),
                score("n4", ExclusionReason.COOLING_DOWN),
                score("n5", null)));

        assertEquals("{\"COOLING_DOWN\":2,\"INSUFFICIENT_RESOURCE\":1,\"TEMPERATURE_EXCEEDED\":1}",
                PlacementService.exclusionCodesOf(result));
        assertNull(PlacementService.exclusionCodesOf(SchedulingResult.failure("후보 없음")), "후보 목록이 비면 null");
        assertNull(PlacementService.exclusionCodesOf(null));
    }
}

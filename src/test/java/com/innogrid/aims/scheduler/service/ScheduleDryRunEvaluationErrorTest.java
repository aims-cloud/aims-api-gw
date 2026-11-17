package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.innogrid.aims.scheduler.dto.ScheduleDryRunResponse;
import com.innogrid.aims.scheduler.dto.ScheduleRequest;
import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.ScheduleRun;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.repository.ScheduleRepository;
import com.innogrid.aims.scheduler.repository.WorkloadRepository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 스케줄 모의 실행 — 엔진 평가가 예외를 던져도 그 건만 실패로 두고 나머지 건을 계속 평가한다.
 *
 * <p>실제 실행은 한 건의 예외로 그 회차 전체를 끝내지만(RuntimeException은 「실행 중 오류」, LinkageError는 「실행 중단」 ·
 * docs/13 2장 주의 14), 모의 실행은 운영자가
 * 결과를 미리 보는 경로라 예외 건을 「평가 중 오류: …」 실패로 적고 다음 건으로 넘어간다. 이 차이는 문서에 적힌 의도된
 * 동작이므로 실제 실행과 같다고 확인하지 않는다.</p>
 *
 * <pre>
 * 노드      8코어 1대 (수집기 대역), CPU 10%, 100W / 정격 500W
 * 엔진      weighted를 스파이로 두고, 이름이 boom으로 시작하면 RuntimeException · nocls면 LinkageError를 던진다
 * 워크로드  GPU 종류 4건: ok1 CRITICAL · boom HIGH · nocls NORMAL · ok2 LOW (모두 1코어, 우선순위 순)
 *           CONTAINER 종류 1건: boom-solo (전부 실패 문구 확인용)
 * </pre>
 */
@SpringBootTest
class ScheduleDryRunEvaluationErrorTest {

    private static final String PREFIX = "sde-";
    private static final String NODE = "sde-node:9100";

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleExecutionService executionService;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired WorkloadRepository workloadRepository;
    @MockitoBean NodeResourceCollector collector;
    @MockitoSpyBean WeightedScoreScheduler weighted;

    @AfterEach
    void clean() {
        workloadRepository.findAll().stream().filter(w -> w.getName().startsWith(PREFIX)).forEach(workloadRepository::delete);
        scheduleRepository.findAll().stream().filter(s -> s.getScheduleId().startsWith(PREFIX))
                .forEach(s -> scheduleService.delete(List.of(s.getId())));
    }

    @DisplayName("엔진 평가가 예외를 던지면 그 건만 평가 중 오류로 실패하고 나머지는 계속 평가하며, 전부 실패면 그 사유가 결과 문구에 실린다")
    @Test
    void an_evaluation_error_fails_only_that_workload() {
        when(collector.collect()).thenAnswer(inv -> new NodeResourceCollector.Snapshot(
                List.of(new NodeResource("mdc-01", NODE, 8, 65_536, 0, 10, 20, 0, 100, 500, 0, 0, 0)), List.of()));
        Workload ok1 = save("ok1", Workload.WorkloadPriority.CRITICAL, Workload.ResourceType.GPU);
        Workload boom = save("boom", Workload.WorkloadPriority.HIGH, Workload.ResourceType.GPU);
        Workload nocls = save("nocls", Workload.WorkloadPriority.NORMAL, Workload.ResourceType.GPU);
        Workload ok2 = save("ok2", Workload.WorkloadPriority.LOW, Workload.ResourceType.GPU);
        save("boom-solo", Workload.WorkloadPriority.NORMAL, Workload.ResourceType.CONTAINER);
        doThrow(new IllegalStateException("엔진 대역 오류")).when(weighted)
                .schedule(argThat(w -> w != null && w.getName().startsWith(PREFIX + "boom")), any(PlacementContext.class));
        doThrow(new NoClassDefFoundError("엔진 대역 클래스")).when(weighted)
                .schedule(argThat(w -> w != null && (PREFIX + "nocls").equals(w.getName())), any(PlacementContext.class));
        long mixed = schedule("mixed", Workload.ResourceType.GPU);
        long solo = schedule("solo", Workload.ResourceType.CONTAINER);

        ScheduleDryRunResponse dry = assertDoesNotThrow(() -> executionService.dryRun(mixed));

        assertEquals(List.of(ok1.getId(), boom.getId(), nocls.getId(), ok2.getId()),
                dry.decisions().stream().map(ScheduleDryRunResponse.DryRunDecision::workloadId).toList(), "예외 뒤 건도 평가한다");
        assertEquals(ScheduleRun.RunStatus.COMPLETED, dry.status());
        assertEquals(4, dry.targetCount());
        assertEquals(2, dry.successCount());
        assertEquals(2, dry.failedCount());
        assertEquals("대상 4건 / 성공 2건 / 실패 2건", dry.message());
        assertEquals(ScheduleRun.SnapshotStatus.OK, dry.clusterSnapshot().status());
        assertEquals(NODE, dry.decisions().get(0).selectedNodeId());
        assertEquals(NODE, dry.decisions().get(3).selectedNodeId(), "예외 건 뒤의 건이 정상 배치된다");

        ScheduleDryRunResponse.DryRunDecision runtime = dry.decisions().get(1);
        assertFalse(runtime.success());
        assertEquals("평가 중 오류: 엔진 대역 오류", runtime.reason());
        assertNull(runtime.selectedNodeId());
        assertNull(runtime.exclusionCodes(), "후보 점수 없이 실패로 적히므로 제외 코드가 없다");
        assertEquals(0.0, runtime.finalScore());
        ScheduleDryRunResponse.DryRunDecision linkage = dry.decisions().get(2);
        assertFalse(linkage.success());
        assertEquals("평가 중 오류: 엔진 대역 클래스", linkage.reason(), "LinkageError 도 그 건만 실패로 둔다");

        ScheduleDryRunResponse allFailed = assertDoesNotThrow(() -> executionService.dryRun(solo));

        assertEquals(ScheduleRun.RunStatus.FAILED, allFailed.status());
        assertEquals(1, allFailed.decisions().size());
        assertEquals("평가 중 오류: 엔진 대역 오류", allFailed.decisions().get(0).reason());
        assertEquals("대상 1건 전부 배치 실패 — 평가 중 오류: 엔진 대역 오류", allFailed.message(), "전부 실패면 첫 예외 사유가 문구에 실린다");
    }

    private long schedule(String key, Workload.ResourceType type) {
        return scheduleService.create(new ScheduleRequest(PREFIX + key, PREFIX + key, type, "0 0 2 * * ?",
                false, true, PREFIX, "weighted", null, null, null, null)).id();
    }

    private Workload save(String name, Workload.WorkloadPriority priority, Workload.ResourceType type) {
        Workload w = new Workload(PREFIX + name, 1, 64);
        w.setResourceType(type);
        w.setPriority(priority);
        return workloadRepository.saveAndFlush(w);
    }
}

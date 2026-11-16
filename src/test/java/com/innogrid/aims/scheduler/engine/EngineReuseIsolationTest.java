package com.innogrid.aims.scheduler.engine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.PlacementContext;
import com.innogrid.aims.scheduler.model.SchedulingResult;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엔진 객체를 다시 써도 앞선 호출의 흔적이 결과에 남지 않는다.
 *
 * <p>엔진은 싱글턴 빈이라 호출마다 같은 객체가 쓰인다. 호출 안에서만 살아야 할 값(최고점·계측 집계·승자의 계측 여부)이
 * 필드로 옮겨지거나, 제약 평가기가 호출 사이에 공유되면 앞선 호출의 냉각 기록·집계가 다음 결과에 섞인다.
 * 기준 파일 테스트는 시나리오마다 엔진을 새로 만들어 이 결함을 못 보므로 여기서 따로 본다.</p>
 *
 * <p>첫 호출은 계측 노드와 임계 온도를 넘은 노드(냉각 기록이 남는다), 두 번째 호출은 모두 미계측이고 첫 호출의 뜨거운 노드와
 * 같은 이름의 노드를 넣는다. 기대값은 새 엔진으로 두 번째 입력만 돌린 결과이고, 재사용한 엔진의 두 번째 결과와 문자열로 같아야 한다.
 * 기대값을 먼저 계산해야 공유된 평가기의 냉각 기록이 기대값 쪽에 섞이지 않는다.</p>
 */
class EngineReuseIsolationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @DisplayName("같은 엔진 객체로 두 번 돌려도 두 번째 결과는 새 엔진의 결과와 같다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"weighted", "power", "temperature"})
    void reused_engine_matches_fresh_engine(String id) {
        SchedulingPolicy policy = new SchedulingPolicy("reuse", "엔진 재사용");
        Workload workload = new Workload("reuse-w", 1, 512);
        List<NodeResource> first = List.of(node(id + "-cool", 20, 200, 500, 40), node(id + "-hot", 20, 200, 500, 80));
        List<NodeResource> second = List.of(node(id + "-hot", 50, 0, 0, 0), node(id + "-b2", 60, 0, 0, 0));

        String expected = pretty(engine(id).schedule(workload, PlacementContext.of(second, policy)));   // 기대값이 먼저

        SchedulingEngine reused = engine(id);
        SchedulingResult a = reused.schedule(workload, PlacementContext.of(first, policy));
        SchedulingResult b = reused.schedule(workload, PlacementContext.of(second, policy));

        // 입력이 뜻대로 짜였는지 — 첫 호출은 뜨거운 노드를 온도로 제외하고, 두 번째 호출은 같은 이름의 노드를 고른다
        assertEquals(ExclusionReason.TEMPERATURE_EXCEEDED, score(a, id + "-hot").exclusionCode(), "첫 호출의 뜨거운 노드");
        assertTrue(score(b, id + "-hot").eligible(), "두 번째 호출에서 같은 이름의 노드는 적격이어야 한다");
        assertEquals(id + "-hot", b.selectedNodeId(), "두 번째 호출의 선택");
        if (id.equals("power")) assertTrue(b.reason().contains("전력 메트릭이 없어"), b.reason());
        if (id.equals("temperature")) assertTrue(b.reason().contains("온도 메트릭이 없어"), b.reason());

        assertEquals(expected, pretty(b), "재사용한 엔진의 두 번째 결과가 새 엔진의 결과와 다르다 — 호출 사이에 남은 값이 있다");
    }

    /** 생성자 인자 순서: mdc, id, cpu, memMb, gpu, cpu%, mem%, disk%, W, capW, °C, rx, tx. */
    private static NodeResource node(String id, double usage, double watts, double capacity, double celsius) {
        return new NodeResource("reuse-mdc", id, 8, 16_384, 0, usage, usage, 40, watts, capacity, celsius, 1_000, 500);
    }

    private static SchedulingEngine engine(String id) {
        return switch (id) {
            case "weighted" -> new WeightedScoreScheduler();
            case "power" -> new PowerAwareScheduler();
            case "temperature" -> new TemperatureAwareScheduler();
            default -> throw new IllegalArgumentException(id);
        };
    }

    private static SchedulingResult.NodeScore score(SchedulingResult r, String nodeId) {
        return r.candidateScores().stream().filter(c -> c.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private String pretty(SchedulingResult r) {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(r);
    }
}

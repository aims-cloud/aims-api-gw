package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.engine.WeightedScoreScheduler;
import com.innogrid.aims.scheduler.engine.constraint.ConstraintEvaluator;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.ExclusionReason;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 판 지표가 세는 <b>열 막힘 횟수</b>가 까닭 코드로 바뀐 뒤에도 같은 후보를 센다.
 *
 * <p>예전 셈은 사유 문장에 "온도"·"냉각" 이 들었는지 보는 부분 검색이었다. 이 테스트는 같은 후보 집합에서
 * 두 방식이 <b>같은 수</b>를 내는지 확인해, 지표 값이 움직이지 않았음을 보인다. 문자열 방식은 여기에만
 * 남겨 두어 비교 기준으로 쓴다.</p>
 */
class ThermalExclusionCountTest {

    private final WeightedScoreScheduler engine = new WeightedScoreScheduler();

    @DisplayName("열로 막힌 후보를 까닭 코드로 세면 옛 문자열 셈과 같은 수가 나온다")
    @Test
    void code_based_count_matches_the_old_string_count() {
        SchedulingResult r = engine.schedule(workload(1, 512), thermalContext());

        int byCode = 0;
        int byText = 0;
        for (NodeScore s : r.candidateScores()) {
            if (s.exclusionCode() != null && s.exclusionCode().isThermal()) byCode++;
            String m = s.exclusionReason();
            if (m != null && (m.contains("온도") || m.contains("냉각"))) byText++;
        }

        assertTrue(byCode > 0, "열로 막힌 후보가 실제로 있어야 비교가 성립한다");
        assertEquals(byText, byCode, "까닭 코드 셈과 옛 문자열 셈이 갈렸다 — 판 지표 값이 움직인다");
    }

    @DisplayName("판 실행의 열 막힘 계수가 사전 등록 값과 같다")
    @Test
    void the_board_metric_counts_only_thermal_exclusions() {
        SchedulingResult r = engine.schedule(workload(1, 512), mixedContext());

        long thermal = r.candidateScores().stream()
                .filter(s -> s.exclusionCode() != null && s.exclusionCode().isThermal()).count();
        long excluded = r.candidateScores().stream().filter(s -> !s.eligible()).count();
        long power = r.candidateScores().stream()
                .filter(s -> s.exclusionCode() != null && s.exclusionCode().isPower()).count();

        assertEquals(4, r.candidateScores().size(), "후보 넷 — 열 둘·전력 하나·적격 하나");
        assertEquals(2, thermal, "열로 막힌 후보만 센다");
        assertEquals(1, power, "전력으로 막힌 후보는 열 막힘이 아니다");
        assertEquals(3, excluded, "막힌 후보 전체는 셋 — 열 계수가 전체를 세면 이 값과 같아져 갈린다");
        assertTrue(thermal < excluded, "열 계수가 막힌 후보 전체를 세면 안 된다");
        assertTrue(thermal != power, "열 계수가 전력 묶음을 세면 안 된다");
    }

    @DisplayName("판이 쓰는 셈이 열 까닭만 세고 다른 까닭은 세지 않는다")
    @Test
    void the_production_counter_counts_only_thermal_codes() {
        SchedulingResult r = engine.schedule(workload(1, 512), mixedContext());

        assertEquals(2, PlacementBenchmarkService.countThermalExclusions(r),
                "판 지표가 세는 셈 — 열 둘만 세야 한다. 막힌 후보 전체(3)나 전력 묶음(1)을 세면 여기서 갈린다");

        SchedulingResult none = engine.schedule(workload(1, 512),
                PlacementContext.of(List.of(new NodeResource("mdc-a", "n-ok", 64, 65536, 0, 10, 10, 15, 100, 1000, 30, 0, 0)),
                        new SchedulingPolicy("p", "d"), ConstraintEvaluator.withDefaults()));
        assertEquals(0, PlacementBenchmarkService.countThermalExclusions(none), "막힌 후보가 없으면 0");
    }

    @DisplayName("열이 아닌 까닭은 열 막힘으로 세지 않는다")
    @Test
    void non_thermal_exclusions_are_not_counted() {
        NodeResource busy = new NodeResource("mdc-a", "n-cpu", 64, 65536, 0, 95, 10, 15, 400, 1000, 40, 0, 0);
        SchedulingResult r = engine.schedule(workload(1, 512),
                PlacementContext.of(List.of(busy), new SchedulingPolicy("p", "d"), ConstraintEvaluator.withDefaults()));

        NodeScore s = r.candidateScores().get(0);
        assertEquals(ExclusionReason.CPU_USAGE_EXCEEDED, s.exclusionCode());
        assertTrue(!s.exclusionCode().isThermal(), "CPU 사용률 초과는 열 막힘이 아니다");
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    /** 온도 계열 셋이 각각 걸리는 후보를 담은 컨텍스트 — 실측 초과·워크로드 상한·배치 후 예상. */
    private static PlacementContext thermalContext() {
        SchedulingPolicy policy = new SchedulingPolicy("p", "d");
        policy.setTemperatureAwareEnabled(true);
        List<NodeResource> nodes = List.of(
                new NodeResource("mdc-a", "n-over", 64, 65536, 0, 10, 10, 15, 400, 1000, 90, 0, 0),
                new NodeResource("mdc-a", "n-near", 64, 65536, 0, 10, 10, 15, 400, 1000, 74, 0, 0),
                new NodeResource("mdc-a", "n-ok", 64, 65536, 0, 10, 10, 15, 400, 1000, 30, 0, 0));
        return PlacementContext.of(nodes, policy, ConstraintEvaluator.withDefaults());
    }

    /** 열 둘·전력 하나·적격 하나 — 열 계수가 무엇을 세는지 값으로 가른다. */
    private static PlacementContext mixedContext() {
        SchedulingPolicy policy = new SchedulingPolicy("p", "d");
        policy.setTemperatureAwareEnabled(true);
        policy.setPowerAwareEnabled(true);
        List<NodeResource> nodes = List.of(
                new NodeResource("mdc-a", "n-hot", 64, 65536, 0, 10, 10, 15, 400, 1000, 90, 0, 0),
                new NodeResource("mdc-a", "n-warm", 4, 65536, 0, 10, 10, 15, 400, 1000, 74, 0, 0),
                new NodeResource("mdc-a", "n-power", 64, 65536, 0, 10, 10, 15, 1000, 1000, 30, 0, 0),
                new NodeResource("mdc-a", "n-ok", 64, 65536, 0, 10, 10, 15, 100, 1000, 30, 0, 0));
        return PlacementContext.of(nodes, policy, ConstraintEvaluator.withDefaults());
    }

    private static Workload workload(int cpu, int memoryMb) {
        return new Workload("w", cpu, memoryMb);
    }
}

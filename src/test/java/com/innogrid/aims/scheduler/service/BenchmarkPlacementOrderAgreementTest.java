package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.benchmark.BenchmarkWorkloads;
import com.innogrid.aims.scheduler.entity.Workload;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 배치 비교의 정렬({@code BenchmarkWorkloads.inPlacementOrder})이 실행 경로의 정렬
 * ({@code ScheduleExecutionService.BY_PRIORITY_THEN_SIZE})과 같은 순서를 내는지 고정한다.
 *
 * <p>이 테스트를 {@code com.innogrid.aims.scheduler.service} 패키지에 둔 것은 {@code
 * BY_PRIORITY_THEN_SIZE} 가 package-private 상수이기 때문이다 — <b>프로덕션 가시성을 넓히지 않고</b>
 * 접근해서, 벤치마크 쪽 정렬과 실행 경로의 정렬이 조용히 발산하는 것을 막는다. 둘 중 하나만 고치고
 * 다른 하나를 안 고치면 여기가 먼저 빨간불이 된다.</p>
 */
class BenchmarkPlacementOrderAgreementTest {

    @DisplayName("배치 비교의 정렬이 실행 경로의 정렬과 같다")
    @Test
    void benchmark_placement_order_agrees_with_execution_order() {
        BenchmarkWorkloads workloads =
                BenchmarkWorkloads.load("classpath:scheduler/benchmark-workloads-heavytail.yml");
        List<Workload> sampled = workloads.sample(300);
        assertFalse(sampled.isEmpty());

        List<Workload> byBenchmarkOrder = BenchmarkWorkloads.inPlacementOrder(sampled);

        List<Workload> byExecutionOrder = new ArrayList<>(sampled);
        byExecutionOrder.sort(ScheduleExecutionService.BY_PRIORITY_THEN_SIZE);

        assertEquals(byExecutionOrder.stream().map(Workload::getName).toList(),
                byBenchmarkOrder.stream().map(Workload::getName).toList(),
                "두 정렬이 같은 입력에서 다른 순서를 내면, 벤치마크가 재는 배치 순서가 실제 실행 순서와 어긋난다");
    }
}

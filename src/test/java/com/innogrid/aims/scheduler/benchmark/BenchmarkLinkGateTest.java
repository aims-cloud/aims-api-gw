package com.innogrid.aims.scheduler.benchmark;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;
import com.innogrid.aims.scheduler.model.LinkMatrix;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 링크 게이트가 <b>링크 판에서만</b> 켜진다는 사실을 고정한다.
 *
 * <p>게이트는 정책 {@code affinityEnabled} <b>와</b> 워크로드 {@code dataMdcId} 둘 다 있어야 켜진다. 워크로드 분포 파일은
 * {@code dataMdcId} 를 넣지 않으므로, 링크 판이 순환 배정을 하기 전까지는 어느 판에서도 링크 코드가 도달하지 않는다 — 기존 6판의
 * 기준선이 그대로인 이유다. 링크 판은 판 파일의 {@code links:} 절이 원장 노릇을 하고 데이터 MDC 를 순환 배정해 그 경로를 처음으로 연다.</p>
 */
class BenchmarkLinkGateTest {

    private static final String HEAVYTAIL = "classpath:scheduler/benchmark-workloads-heavytail.yml";
    private static final String UNIFORM = "classpath:scheduler/benchmark-workloads-uniform.yml";

    @DisplayName("워크로드 분포 파일은 두 분포 모두 dataMdcId 가 없어 배정 없이는 게이트가 켜질 수 없다")
    @Test
    void benchmark_workloads_never_carry_data_mdc() {
        for (String location : List.of(HEAVYTAIL, UNIFORM)) {
            List<Workload> sample = BenchmarkWorkloads.load(location).sample(1_000);
            assertFalse(sample.isEmpty(), location);
            assertTrue(sample.stream().allMatch(w -> w.getDataMdcId() == null), location + " — dataMdcId 없음");

            SchedulingPolicy affinityOn = new SchedulingPolicy("p", "d");
            affinityOn.setAffinityEnabled(true);
            PlacementContext ctx = PlacementContext.of(List.of(), affinityOn);
            assertTrue(sample.stream().noneMatch(ctx::linkAware),
                    location + " — 정책이 affinity 를 켜도 워크로드에 데이터 MDC 가 없어 게이트는 꺼져 있다");
        }
    }

    @DisplayName("링크 판만 링크 행렬이 비어 있지 않고, 노드 블록은 주 결과판과 같다")
    @Test
    void only_the_link_board_has_a_link_matrix_and_shares_the_main_nodes() {
        BenchmarkClusters clusters = new BenchmarkClusters();
        SyntheticCluster main = clusters.get(BenchmarkClusters.MAIN);
        for (String boardId : clusters.boardIds()) {
            SyntheticCluster board = clusters.get(boardId);
            LinkMatrix matrix = BenchmarkLinks.matrixOf(board);
            assertTrue(board.nodes().stream().allMatch(n -> n.mdcId() != null && !n.mdcId().isBlank()), boardId + " — 노드마다 MDC 라벨");
            assertTrue(board.mdcCount() >= 2, boardId + " — MDC 둘 이상");
            if (BenchmarkClusters.LINK.equals(boardId)) {
                assertEquals(2, matrix.size(), "등록 두 쌍");
                assertTrue(matrix.between("bm-mdc-a", "bm-mdc-b").isPresent());
                assertTrue(matrix.between("bm-mdc-a", "bm-mdc-c").isPresent());
                assertTrue(matrix.between("bm-mdc-b", "bm-mdc-c").isEmpty(), "b-c 는 미등록 — unknownLinkScore 경로");
                assertEquals(main.nodes(), board.nodes(), "링크 판의 노드 블록은 주 결과판과 값까지 같아야 판 사이 차이를 링크로 돌릴 수 있다");
                assertEquals(main.powerExponent(), board.powerExponent());
                assertEquals(main.seed(), board.seed());
            } else {
                assertSame(LinkMatrix.EMPTY, matrix, boardId + " — 링크 없는 판은 빈 행렬 그대로");
            }
        }
    }

    @DisplayName("링크 행렬을 실은 컨텍스트 사본은 그 행렬을 그대로 든다 — 전략과 프로브가 같은 행렬을 본다")
    @Test
    void context_copy_carries_the_matrix() {
        BenchmarkClusters clusters = new BenchmarkClusters();
        LinkMatrix matrix = BenchmarkLinks.matrixOf(clusters.get(BenchmarkClusters.LINK));
        SchedulingPolicy policy = new SchedulingPolicy("p", "d");
        PlacementContext ctx = BenchmarkContexts.copyOf(List.of(), policy, BenchmarkContexts.freshEvaluator(new com.innogrid.aims.scheduler.config.SchedulerProperties()), matrix);
        assertSame(matrix, ctx.linkMatrix());
        assertSame(LinkMatrix.EMPTY, BenchmarkContexts.copyOf(List.of(), policy, BenchmarkContexts.freshEvaluator(new com.innogrid.aims.scheduler.config.SchedulerProperties())).linkMatrix());
    }
}

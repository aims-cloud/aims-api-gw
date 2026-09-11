package com.innogrid.aims.scheduler.benchmark;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;
import com.innogrid.aims.scheduler.model.PlacementContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 판정판 경로에서 링크 점수가 <b>한 번도 계산되지 않는다</b>는 사실을 고정한다.
 *
 * <p>미등록 원격 MDC 의 링크 점수를 1.0 에서 0.5 로 바꾼 뒤에도 여섯 판의 결과와 기준선이 그대로인 이유가
 * 이 시험이다. 게이트는 정책 {@code affinityEnabled} <b>와</b> 워크로드 {@code dataMdcId} 둘 다 있어야 켜지는데,
 * 판정판 워크로드 생성기는 {@code dataMdcId} 를 넣지 않는다. 그러므로 정책 사본이 affinity 를 켜고 있어도
 * 링크 코드는 도달하지 않는다. 누군가 판에 데이터 MDC 를 넣으면 이 시험이 먼저 깨져 "기준선이 바뀐다" 를 알린다.</p>
 */
class BenchmarkLinkGateUnreachableTest {

    private static final String HEAVYTAIL = "classpath:scheduler/benchmark-workloads-heavytail.yml";
    private static final String UNIFORM = "classpath:scheduler/benchmark-workloads-uniform.yml";

    @DisplayName("판정판 워크로드는 두 분포 모두 dataMdcId 가 없어 링크 게이트가 켜질 수 없다")
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

    @DisplayName("여섯 판 어디에도 링크 정의가 없고 노드 MDC 는 있으므로 링크가 반영되려면 워크로드 쪽이 바뀌어야 한다")
    @Test
    void boards_have_mdc_labels_but_no_links() {
        BenchmarkClusters clusters = new BenchmarkClusters();
        for (String boardId : clusters.boardIds()) {
            SyntheticCluster board = clusters.get(boardId);
            List<SyntheticCluster.Node> nodes = board.nodes();
            assertFalse(nodes.isEmpty(), boardId);
            assertTrue(nodes.stream().allMatch(n -> n.mdcId() != null && !n.mdcId().isBlank()),
                    boardId + " — 노드마다 MDC 라벨은 있다(라벨 없음 경로가 아니다)");
            assertTrue(board.mdcCount() >= 2, boardId + " — MDC 가 둘 이상이라 원격 쌍이 존재하지만 링크 정의는 없다");
        }
    }
}

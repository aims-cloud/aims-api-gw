package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.benchmark.BenchmarkClusters;
import com.innogrid.aims.scheduler.benchmark.BenchmarkRules;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyResponse;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyResponse.NodeEfficiency;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.metrics.SyntheticCluster;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 사용 코어 환산 효율 · 코어 기준 순위를 배치 전략 비교 판 파일로 확인한다(스프링 없음).
 *
 * <p>판 노드마다 기준 사용률 시점의 스냅샷을 만든다 — 전력 = 유휴 + (정격 − 유휴) × (사용률/100)^β(그 판의 β), 엔진에 알려 준 정격은
 * 노드 조회와 같이 {@code believedCapacityWatts}. IPMI가 없는 노드는 전력 0이라 평가에서 빠진다.</p>
 *
 * <p>코어당 한계 전력은 기준 사용률 시점의 기울기로 잰다 — β · (정격 − 유휴) · (사용률/100)^(β−1) ÷ 코어. 이 정의로
 * 판정 회차 4 사전 추정(노드 배치 스코어 PEI 항 기준)의 순위 상관이 나온다: 판정판 원값 −0.27 · 코어 기준 +0.55,
 * 방향판 코어 기준 +0.75.
 * 방향판 원값은 정밀값으로 −0.43이지만 응답의 peiRaw가 소수 넷째 자리까지 반올림이라 bm-02 · bm-05가 0.0914로 같아져 −0.38이다.
 * 기준값이 코어 수로 나눈 값이라 코어 수를 곱한 지표에 유리한 구조이고 노드가 9대뿐이라, 이 상관은 통계 근거가 아니라 노드 배치 스코어 PEI 항과
 * 같은 방향인지 보는 확인이다. 값은 표준 출력에 남기고 확인은 부호만 한다.</p>
 */
class PowerEfficiencyCoreBasisBoardTest {

    /** 가동 노드 기준 — 판의 유휴 노드는 기준 사용률이 2% 안팎이라 10%로 가르면 두 판 모두 9대다(엔진의 통전 문턱 5%와는 쓰임이 다르다). */
    private static final double ACTIVE_CPU_PERCENT = 10.0;

    private final SchedulerProperties properties = new SchedulerProperties();
    private final PowerModel powerModel = new PowerModel(properties);
    private final PowerEfficiencyService service = new PowerEfficiencyService(mock(SchedulingService.class), powerModel,
            mock(VictoriaMetricsClient.class), properties);
    private final BenchmarkClusters boards = new BenchmarkClusters();

    @DisplayName("등록 판 전부에서 포화하지 않은 노드의 코어 기준 순위는 배치 점수 PEI(코어 비율 반영) 순서와 같다")
    @Test
    void core_rank_follows_placement_pei_on_every_board() {
        int compared = 0;
        for (String boardId : boards.boardIds()) {
            SyntheticCluster cluster = boards.get(boardId);
            List<NodeResource> snapshot = snapshot(cluster);
            double meanCores = PowerModel.meanPositiveCores(snapshot);
            PowerEfficiencyResponse result = service.evaluate(snapshot);

            List<NodeEfficiency> rows = new ArrayList<>();
            List<Double> placement = new ArrayList<>();
            for (int i = 0; i < snapshot.size(); i++) {
                NodeEfficiency row = result.nodes().get(i);
                double pei = powerModel.placementPei(snapshot.get(i), meanCores);
                if (row.coreRank() == null || pei >= 1.0) continue;   // 미계측 · 포화(1.0에서 잘려 동점) 노드는 순서를 말하지 못한다
                rows.add(row);
                placement.add(pei);
            }
            assertFalse(rows.size() < 2, boardId + " 판에 비교할 노드가 둘 이상 있어야 한다");
            for (int a = 0; a < rows.size(); a++) {
                for (int b = 0; b < rows.size(); b++) {
                    if (placement.get(a) > placement.get(b) + 1e-12 && !rows.get(a).usedCoresPerKw().equals(rows.get(b).usedCoresPerKw())) {
                        assertTrue(rows.get(a).coreRank() < rows.get(b).coreRank(), boardId + " · " + rows.get(a).nodeId() + "(배치 PEI "
                                + placement.get(a) + ") 가 " + rows.get(b).nodeId() + "(" + placement.get(b) + ") 보다 앞서야 한다");
                        compared++;
                    }
                }
            }
        }
        assertTrue(compared > 100, "비교한 노드 쌍 수: " + compared);
    }

    @DisplayName("판정판과 방향판의 가동 노드에서 코어 기준 효율은 코어당 한계 전력이 낮은 순서를 따르고 원값은 거꾸로 간다")
    @Test
    void core_basis_tracks_marginal_power_per_core_on_judgment_boards() {
        StringBuilder record = new StringBuilder();
        for (String boardId : List.of(BenchmarkRules.JUDGMENT_BOARD, BenchmarkRules.DIRECTION_CHECK_BOARD)) {
            SyntheticCluster cluster = boards.get(boardId);
            List<NodeResource> snapshot = snapshot(cluster);
            PowerEfficiencyResponse result = service.evaluate(snapshot);

            List<Double> raw = new ArrayList<>();
            List<Double> core = new ArrayList<>();
            List<Double> inverseMarginal = new ArrayList<>();
            for (SyntheticCluster.Node node : cluster.nodes()) {
                if (!node.ipmi() || node.cpuUsage() < ACTIVE_CPU_PERCENT) continue;   // 가동 노드만 — 유휴 노드는 일의 양이 0에 가까워 순위가 의미 없다
                NodeEfficiency row = result.nodes().stream().filter(n -> n.nodeId().equals(node.nodeId())).findFirst().orElseThrow();
                double beta = cluster.powerExponent();
                double marginalPerCore = beta * (node.capacityWatts() - node.idleWatts())
                        * Math.pow(node.cpuUsage() / 100.0, beta - 1) / node.cores();
                raw.add(row.peiRaw());
                core.add(row.usedCoresPerKw());
                inverseMarginal.add(1.0 / marginalPerCore);
            }
            double rawRho = spearman(raw, inverseMarginal);
            double coreRho = spearman(core, inverseMarginal);
            record.append(String.format("%s 가동 %d대 · 원값 %.2f · 코어 기준 %.2f%n", boardId, raw.size(), rawRho, coreRho));
            assertEquals(9, raw.size(), boardId + " 가동 노드 수");
            assertTrue(coreRho > 0 && rawRho < 0, boardId + " — 코어 기준은 양, 원값은 음이어야 한다: " + record);
        }
        System.out.print(record);
    }

    /** 판 노드의 기준 사용률 시점 스냅샷 — 노드 순서는 판 파일 순서 그대로. */
    private static List<NodeResource> snapshot(SyntheticCluster cluster) {
        List<NodeResource> nodes = new ArrayList<>();
        for (SyntheticCluster.Node n : cluster.nodes()) {
            double u = n.cpuUsage();
            double power = n.ipmi() ? n.idleWatts() + (n.capacityWatts() - n.idleWatts()) * Math.pow(u / 100.0, cluster.powerExponent()) : 0.0;
            NodeResource node = new NodeResource(n.mdcId(), n.nodeId(), n.cores(), n.memoryMb(), n.gpus(),
                    u, n.memoryUsage(), n.diskUsage(), power, n.believedCapacityWatts(), n.baseTemperature(), 0, 0);
            node.setPowerSource(n.ipmi() ? NodeResource.PowerSource.IPMI : NodeResource.PowerSource.NONE);
            nodes.add(node);
        }
        return nodes;
    }

    /** 스피어만 순위 상관 — 동점은 평균 순위. */
    private static double spearman(List<Double> x, List<Double> y) {
        double[] rx = ranks(x);
        double[] ry = ranks(y);
        int n = rx.length;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += rx[i]; my += ry[i]; }
        mx /= n; my /= n;
        double num = 0, dx = 0, dy = 0;
        for (int i = 0; i < n; i++) {
            num += (rx[i] - mx) * (ry[i] - my);
            dx += (rx[i] - mx) * (rx[i] - mx);
            dy += (ry[i] - my) * (ry[i] - my);
        }
        return num / Math.sqrt(dx * dy);
    }

    private static double[] ranks(List<Double> values) {
        Integer[] order = new Integer[values.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(values.get(a), values.get(b)));
        double[] rank = new double[values.size()];
        for (int i = 0; i < order.length; ) {
            int j = i;
            while (j + 1 < order.length && values.get(order[j + 1]).equals(values.get(order[i]))) j++;
            for (int k = i; k <= j; k++) rank[order[k]] = (i + j) / 2.0 + 1;
            i = j + 1;
        }
        return rank;
    }
}

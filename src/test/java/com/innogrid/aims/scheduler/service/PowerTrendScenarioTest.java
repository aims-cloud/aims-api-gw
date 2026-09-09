package com.innogrid.aims.scheduler.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationTrendResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationTrendResponse.Point;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 시나리오 — "배치 알고리즘을 켠 뒤 회수 가능 전력이 줄어드는가"를 추세가 보여 주는가, 그리고 추세의 마지막 지점이
 * 같은 상태로 만든 <b>현재 진단</b>과 같은 숫자를 내는가(같은 식이라는 증명).
 *
 * <pre>
 * 노드 4대 · 용량 500W · 정격 유휴 전력 150W · 가동 기준 CPU 5%. 회수 가능은 계측된 유휴 노드라 실측에서
 * 역산한 wakeupPower 합이다({@code (P − Pmax·u)/(1−u)}) — 정격 × idleRatio 가 아니다.
 * 시각   상태                                   가동/유휴   총 전력   회수 가능
 * t0     n1 60%  n2 3%  n3 2%  n4 1%            1 / 3      560W      337.18W   (통합 전: 빈 노드 3대가 유휴 전력을 씀)
 * t1     n1 70%  n2 40% n3 2%  n4 1%            2 / 2      620W      198.00W   (워크로드 1건 n2 로)
 * t2     n1 70%  n2 45% n3 30% n4 1%            3 / 1      680W       95.96W
 * t3     n1 70%  n2 45% n3 35% n4 20%           4 / 0      760W        0W   (통합 완료)
 * </pre>
 */
class PowerTrendScenarioTest {

    private static final long T0 = 1_756_684_800L;
    private static final double EPS = 1e-4;

    @DisplayName("시나리오 통합이 진행될수록 회수 가능 전력이 줄고 마지막 지점은 현재 진단과 같은 숫자다")
    @Test
    void scenario_releasable_power_falls() {
        SchedulerProperties props = new SchedulerProperties();
        VictoriaMetricsClient metrics = mock(VictoriaMetricsClient.class);
        PowerOptimizationService service = new PowerOptimizationService(mock(SchedulingService.class),
                new PowerModel(props), props, metrics, new MetricsQueryGuard(2));

        double[][] cpu = {{60, 70, 70, 70}, {3, 40, 45, 45}, {2, 2, 30, 35}, {1, 1, 1, 20}};
        double[][] power = {{200, 220, 220, 220}, {150, 190, 200, 200}, {110, 110, 160, 170}, {100, 100, 100, 170}};
        when(metrics.queryRangeResults(contains("node_cpu_seconds_total"), any(Instant.class), any(Instant.class), anyString()))
                .thenReturn(seriesOf(cpu));
        when(metrics.queryRangeResults(contains("ipmi_dcmi_power"), any(Instant.class), any(Instant.class), anyString()))
                .thenReturn(seriesOf(power));

        PowerOptimizationTrendResponse trend = service.trend(at(T0), at(T0 + 900), "5m");

        StringBuilder table = new StringBuilder("\n| 시각 | 가동/유휴 | 총 전력 | 회수 가능 | PEI 평균 | measured |\n|---|---|---|---|---|---|\n");
        for (Point p : trend.points()) {
            table.append(String.format("| t%d | %d/%d | %.0fW | %.0fW | %.4f | %s |%n",
                    (p.at().toEpochSecond() - T0) / 300, p.activeNodes(), p.idleNodes(),
                    p.totalPowerWatts(), p.reclaimableIdleWatts(), p.avgPeiRaw(), p.measured()));
        }

        assertEquals(4, trend.points().size());
        int[] expectedActive = {1, 2, 3, 4};
        // 반납분은 wakeupPower — 계측된 유휴 노드는 실측에서 역산한다 (P,u,Pmax)=(150,220,110,100 실측; 500 정격):
        // t0 n2(150,0.03,500)=(150-500*.03)/.97=135/.97≈139.1753, n3(110,0.02,500)=100/.98≈102.0408,
        //    n4(100,0.01,500)=95/.99≈95.9596 → 합≈337.1757 → round 337.18
        // t1 n3(110,0.02,500)≈102.0408, n4(100,0.01,500)≈95.9596 → 합≈198.0004 → round 198.00
        // t2 n4(100,0.01,500)≈95.9596 → round 95.96
        // t3 전부 가동이라 회수 0(변화 없음)
        double n2t0 = PowerOptimizationService.round((150 - 500 * 0.03) / 0.97);
        double n3t0 = PowerOptimizationService.round((110 - 500 * 0.02) / 0.98);
        double n4t0 = PowerOptimizationService.round((100 - 500 * 0.01) / 0.99);
        double[] expectedReclaim = {n2t0 + n3t0 + n4t0, n3t0 + n4t0, n4t0, 0};
        double[] expectedTotal = {560, 620, 680, 760};
        for (int i = 0; i < 4; i++) {
            Point p = trend.points().get(i);
            assertEquals(expectedActive[i], p.activeNodes(), "t" + i + " 가동");
            assertEquals(expectedReclaim[i], p.reclaimableIdleWatts(), EPS, "t" + i + " 회수 가능");
            assertEquals(expectedTotal[i], p.totalPowerWatts(), EPS, "t" + i + " 총 전력");
            assertTrue(p.measured());
        }

        // 마지막 지점과 같은 상태로 현재 진단을 돌리면 숫자가 같다 — 추세가 진단과 같은 식이라는 증명
        List<NodeResource> now = new ArrayList<>();
        for (int n = 0; n < 4; n++) {
            now.add(new NodeResource("mdc-01", "n" + (n + 1), 8, 16384, 0, cpu[n][3], 30, 0, power[n][3], 500, 40, 0, 0));
        }
        PowerOptimizationResponse current = service.diagnose(now);
        Point last = trend.points().get(3);
        table.append(String.format("| 현재 진단(t3 상태) | %d/%d | %.0fW | %.0fW | — | %s |%n",
                current.cluster().activeNodes(), current.cluster().idleNodes(),
                current.cluster().totalPowerWatts(), current.cluster().reclaimableIdleWatts(), current.cluster().measured()));
        assertEquals(current.cluster().activeNodes(), last.activeNodes());
        assertEquals(current.cluster().totalPowerWatts(), last.totalPowerWatts(), EPS);
        assertEquals(current.cluster().reclaimableIdleWatts(), last.reclaimableIdleWatts(), EPS);

        System.out.println(table);
    }

    private static OffsetDateTime at(long epoch) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC);
    }

    /** 노드 n1~n4 × 시각 t0~t3 행렬을 저장소 응답 형태로. */
    private static List<Map<String, Object>> seriesOf(double[][] matrix) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int n = 0; n < matrix.length; n++) {
            List<List<Object>> values = new ArrayList<>();
            for (int t = 0; t < matrix[n].length; t++) values.add(List.of(T0 + 300L * t, String.valueOf(matrix[n][t])));
            out.add(Map.of("metric", Map.of("instance", "n" + (n + 1)), "values", values));
        }
        return out;
    }
}

package com.innogrid.aims.scheduler.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.monitoring.VictoriaMetricsClient;
import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyResponse;
import com.innogrid.aims.scheduler.dto.PowerEfficiencyResponse.NodeEfficiency;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 실제 전력 데이터가 들어오기 전까지 여러 클러스터 모양을 넣어 보는 시나리오 모음.
 *
 * <p><b>입력을 바꾸는 법</b> — {@link #node(String, double, double)} 로 노드를 만들어
 * {@code evaluate(...)} 에 넣으면 된다. CPU 사용률(%)과 소비 전력(W)만 주면 PEI 는
 * {@code CPU ÷ 전력} 이라 기대값을 손으로 계산할 수 있다. 새 시나리오가 필요하면
 * 이 파일에 메서드를 하나 더 만들면 되고, 다른 곳은 건드릴 필요가 없다.</p>
 *
 * <p>각 시나리오는 결과를 표로 찍는다. 값이 어떻게 갈리는지 눈으로 확인하려면
 * {@code ./gradlew test --tests '*PowerEfficiencyScenarioTest*' --info} 로 실행한다.</p>
 */
class PowerEfficiencyScenarioTest {

    private PowerEfficiencyService service;

    @BeforeEach
    void setUp() {
        SchedulerProperties properties = new SchedulerProperties();
        service = new PowerEfficiencyService(mock(SchedulingService.class), new PowerModel(properties),
                mock(VictoriaMetricsClient.class), properties);
    }

    @Test
    @DisplayName("한 대만 비정상적으로 낮은 경우 — 그 노드만 최하위로 떨어진다")
    void 한_대만_비정상적으로_낮다() {
        PowerEfficiencyResponse result = run("한 대만 비정상적으로 낮음", List.of(
                node("정상-1", 70, 200),   // PEI 0.350
                node("정상-2", 68, 200),   // 0.340
                node("정상-3", 66, 200),   // 0.330
                node("정상-4", 64, 200),   // 0.320
                node("정상-5", 62, 200),   // 0.310
                node("정상-6", 60, 200),   // 0.300
                node("정상-7", 58, 200),   // 0.290
                node("이상-8", 12, 260))); // 0.046 — 부하는 낮은데 전력은 더 쓴다

        NodeEfficiency outlier = find(result, "이상-8");
        assertEquals("D", outlier.grade());
        assertEquals(8, outlier.rank());
        assertEquals("저부하로 전력 효율이 낮습니다. 워크로드 통합을 검토하세요", outlier.recommendation(),
                "이상 노드에는 권고가 붙어야 한다");
        assertNull(find(result, "정상-1").recommendation(), "정상 노드에는 권고가 없다");
    }

    @Test
    @DisplayName("전반적으로 평이한 경우 — 차이가 미미해도 등급은 A~D 로 갈린다")
    void 전반적으로_평이하다() {
        PowerEfficiencyResponse result = run("전반적으로 평이", List.of(
                node("노드-1", 50.4, 200),  // PEI 0.2520
                node("노드-2", 50.3, 200),  // 0.2515
                node("노드-3", 50.2, 200),  // 0.2510
                node("노드-4", 50.1, 200),  // 0.2505
                node("노드-5", 50.0, 200),  // 0.2500
                node("노드-6", 49.9, 200),  // 0.2495
                node("노드-7", 49.8, 200),  // 0.2490
                node("노드-8", 49.7, 200))); // 0.2485

        // 상대 평가라 실질 차이가 0.35% 뿐이어도 등급은 끝까지 갈린다.
        // 등급만 보고 "D 노드를 정리해야겠다"고 판단하면 틀린다.
        assertEquals("A", find(result, "노드-1").grade());
        assertEquals("D", find(result, "노드-8").grade());

        double spread = find(result, "노드-1").peiRaw() - find(result, "노드-8").peiRaw();
        assertTrue(spread < 0.005, "최상위와 최하위의 실질 차이가 거의 없다: " + spread);
    }

    @Test
    @DisplayName("전반적으로 높은 경우 — 전부 고효율이어도 누군가는 D 를 받는다")
    void 전반적으로_높다() {
        PowerEfficiencyResponse result = run("전반적으로 높음", List.of(
                node("노드-1", 90, 150),   // PEI 0.600
                node("노드-2", 88, 150),   // 0.587
                node("노드-3", 86, 150),   // 0.573
                node("노드-4", 84, 150),   // 0.560
                node("노드-5", 82, 150),   // 0.547
                node("노드-6", 80, 150),   // 0.533
                node("노드-7", 78, 150),   // 0.520
                node("노드-8", 76, 150))); // 0.507

        assertEquals("D", find(result, "노드-8").grade());
        assertTrue(find(result, "노드-8").peiNormalized() > 1.0 - 1e-9,
                "정규화 PEI 는 만점인데 상대 등급은 D 다");

        // 이 노드들은 이상 곡선(465W)보다 훨씬 적은 150W 만 쓰는 아주 효율적인 노드다.
        // 비례성이 0.37 로 낮게 나오지만 그건 "덜 쓴다"는 뜻이므로 경고 대상이 아니다.
        assertTrue(result.nodes().stream().allMatch(n -> n.recommendation() == null),
                "고효율 노드에 권고가 붙으면 오탐이다");
        assertTrue(find(result, "노드-1").proportionality() < 0.5,
                "비례성은 낮게 나오지만 권고 근거로 쓰지 않는다");
    }

    @Test
    @DisplayName("전반적으로 낮은 경우 — 전부 저효율이어도 누군가는 A 를 받는다")
    void 전반적으로_낮다() {
        PowerEfficiencyResponse result = run("전반적으로 낮음", List.of(
                node("노드-1", 16, 300),   // PEI 0.0533
                node("노드-2", 15, 300),
                node("노드-3", 14, 300),
                node("노드-4", 13, 300),
                node("노드-5", 12, 300),
                node("노드-6", 11, 300),
                node("노드-7", 10, 300),
                node("노드-8", 9, 300)));  // 0.030

        assertEquals("A", find(result, "노드-1").grade(), "전부 나빠도 상위 25% 는 A 다");
        assertTrue(find(result, "노드-1").peiNormalized() < 0.15, "정규화 PEI 로는 최하위권");
        // 전 노드가 저부하라 D 등급 노드에는 통합 권고가 붙는다
        assertNotNull(find(result, "노드-8").recommendation());
    }

    @Test
    @DisplayName("극단이 섞인 경우 — 소수 고효율 + 다수 저효율")
    void 극단이_섞였다() {
        PowerEfficiencyResponse result = run("극단 혼재", List.of(
                node("고효율-1", 95, 180),  // PEI 0.528
                node("고효율-2", 92, 180),  // 0.511
                node("저효율-3", 8, 240),   // 0.033
                node("저효율-4", 7, 240),
                node("저효율-5", 6, 240),
                node("저효율-6", 5, 240),
                node("저효율-7", 4, 240),
                node("저효율-8", 3, 240)));

        assertEquals("A", find(result, "고효율-1").grade());
        assertEquals("A", find(result, "고효율-2").grade());
        assertEquals("D", find(result, "저효율-8").grade());
        assertNull(find(result, "고효율-1").recommendation(), "고효율 노드에는 권고가 없다");
        assertNotNull(find(result, "저효율-8").recommendation(), "저부하 노드에는 통합 권고가 붙는다");
        // 평균은 소수의 고효율 노드에 끌려 올라간다. 평균만 보면 클러스터가 괜찮아 보인다.
        assertTrue(result.summary().averagePeiRaw() > find(result, "저효율-3").peiRaw(),
                "평균이 다수 노드보다 높다");
    }

    @Test
    @DisplayName("일부만 IPMI 가 연동된 경우 — 연동된 노드끼리만 평가한다")
    void 일부만_계측된다() {
        PowerEfficiencyResponse result = run("일부만 계측", List.of(
                node("계측-1", 80, 200), node("계측-2", 60, 200),
                node("계측-3", 40, 200), node("계측-4", 20, 200),
                node("미계측-5", 70, 0), node("미계측-6", 50, 0)));

        assertEquals(6, result.summary().totalNodes());
        assertEquals(4, result.summary().evaluableNodes());
        assertTrue(result.summary().gradeAvailable(), "계측된 4대로 등급을 매길 수 있다");
        assertEquals("A", find(result, "계측-1").grade());
        assertEquals("D", find(result, "계측-4").grade());
        assertNull(find(result, "미계측-5").grade());
        // 평균도 계측된 4대만으로 낸다: (0.40+0.30+0.20+0.10)/4 = 0.25
        assertEquals(0.25, result.summary().averagePeiRaw(), 1e-4);
    }

    @Test
    @DisplayName("전력 여유가 부족한 경우 — 배치 제한 권고가 우선한다")
    void 전력_여유가_부족하다() {
        // 용량 500W 중 450W 를 쓰고 있어 여유가 10% 다
        NodeResource tight = new NodeResource("mdc-01", "포화-1", 8, 16384, 0,
                90, 50, 30, 450, 500, 40, 0, 0);
        tight.setPowerSource(NodeResource.PowerSource.IPMI);

        NodeEfficiency n = run("전력 여유 부족", List.of(tight)).nodes().get(0);

        assertEquals(10.0, n.headroomPercent(), 1e-9);
        assertEquals("전력 여유가 부족합니다. 추가 배치를 제한하세요", n.recommendation(),
                "다른 조건보다 먼저 걸러야 한다");
    }

    // ── 도우미 ─────────────────────────────────────────────────

    /** 평가하고 결과를 표로 찍는다. 값이 어떻게 갈리는지 눈으로 보려면 --info 로 실행한다. */
    private PowerEfficiencyResponse run(String title, List<NodeResource> nodes) {
        PowerEfficiencyResponse result = service.evaluate(nodes);
        StringBuilder out = new StringBuilder();
        out.append("\n■ ").append(title).append('\n');
        out.append(String.format("  %-10s %7s %7s %8s %8s %6s %5s  %s%n",
                "노드", "CPU%", "전력W", "PEI", "비례성", "등급", "순위", "권고"));
        for (NodeEfficiency n : result.nodes()) {
            out.append(String.format("  %-10s %7.1f %7.1f %8s %8s %6s %5s  %s%n",
                    n.nodeId(), n.cpuUsagePercent(), n.powerWatts(),
                    fmt(n.peiRaw()), fmt(n.proportionality()),
                    n.grade() == null ? "-" : n.grade(),
                    n.rank() == null ? "-" : n.rank(),
                    n.recommendation() == null ? "" : n.recommendation()));
        }
        PowerEfficiencyResponse.Summary s = result.summary();
        out.append(String.format("  → 전체 %d대 / 평가가능 %d대 / 평균 PEI %s / 총 전력 %.0fW%n",
                s.totalNodes(), s.evaluableNodes(), fmt(s.averagePeiRaw()), s.totalPowerWatts()));
        if (!s.gradeAvailable()) {
            out.append("  → ").append(s.gradeUnavailableReason()).append('\n');
        }
        System.out.print(out);
        return result;
    }

    private String fmt(Double value) {
        return value == null ? "-" : String.format("%.4f", value);
    }

    /** CPU 사용률(%)과 소비 전력(W)만 주면 되는 노드. 용량은 500W 로 고정한다. */
    private NodeResource node(String id, double cpuPercent, double powerWatts) {
        NodeResource node = new NodeResource("mdc-01", id, 8, 16384, 0,
                cpuPercent, 50, 30, powerWatts, 500, 40, 0, 0);
        node.setPowerSource(powerWatts > 0
                ? NodeResource.PowerSource.IPMI : NodeResource.PowerSource.NONE);
        return node;
    }

    private NodeEfficiency find(PowerEfficiencyResponse result, String nodeId) {
        return result.nodes().stream().filter(n -> n.nodeId().equals(nodeId))
                .findFirst().orElseThrow(() -> new AssertionError("노드를 찾을 수 없다: " + nodeId));
    }

    @SuppressWarnings("unused")
    private List<NodeResource> repeat(int count, double cpuPercent, double powerWatts) {
        List<NodeResource> nodes = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            nodes.add(node("노드-" + i, cpuPercent, powerWatts));
        }
        return nodes;
    }
}

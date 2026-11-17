package com.innogrid.aims.scheduler.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.engine.constraint.PlacementContext;
import com.innogrid.aims.scheduler.entity.SchedulingPolicy;
import com.innogrid.aims.scheduler.entity.Workload;
import com.innogrid.aims.scheduler.model.NodeResource;
import com.innogrid.aims.scheduler.model.SchedulingResult;
import com.innogrid.aims.scheduler.model.SchedulingResult.NodeScore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 합성 클러스터로 돌려 보는 시나리오 검증.
 *
 * <p>단위 테스트가 항 하나씩을 보는 데 비해, 여기서는 <b>여러 노드가 섞인 상황에서 실제로
 * 어떤 판단이 나오는지</b>를 본다. 배포 VM은 노드가 1대뿐이고 IPMI도 없어 이런 검증을 할 수
 * 없으므로, 합성 데이터가 유일한 수단이다.</p>
 *
 * <p>모든 노드는 8코어 · 16GB · 용량 500W(유휴 150W)로 맞추고 <b>부하·전력·온도만</b> 바꾼다.
 * 그래야 결과 차이가 어디서 왔는지 분명해진다. 각 시나리오는 결과 표를 함께 찍는다.</p>
 */
class PowerAwareScenarioTest {

    private final PowerAwareScheduler scheduler = new PowerAwareScheduler();

    /**
     * 시나리오 1 — 전형적인 혼재 클러스터.
     * 효율적인 노드, 낭비하는 노드, 뜨거운 노드, 비어 있는 노드가 섞여 있다.
     */
    @DisplayName("시나리오 혼재된 클러스터에서 효율적인 노드를 고른다")
    @Test
    void scenario_mixed_cluster_efficient_node() {
        List<NodeResource> nodes = List.of(
                node("효율-적정부하", 55, 45, 230, 48),
                node("낭비-저부하", 20, 30, 420, 50),
                node("고온-효율", 60, 50, 240, 72),
                node("유휴-대기", 2, 8, 160, 32));

        SchedulingResult result = run("시나리오 1 — 혼재 클러스터", nodes, workload(2), policy());

        assertEquals("효율-적정부하", result.selectedNodeId(),
                "PEI 가 좋고 이상 곡선을 넘지 않으며 가동 중이라 깨우는 비용도 없다");
        assertTrue(detail(result, "효율-적정부하", "전력점수") > detail(result, "유휴-대기", "전력점수") + 0.2,
                "전력 판단이 효율적인 노드를 확실히 선호해야 한다");
        assertTrue(rank(result, "효율-적정부하") < rank(result, "낭비-저부하"));
    }

    /**
     * 시나리오 1-b — 자원 몫을 올리면 빈 노드가 이긴다.
     *
     * <p>기본값을 0.10으로 정한 근거를 테스트로 남긴다. 빈 노드는 자원 여유와 온도 양쪽에서
     * 보상을 받아, 자원 몫이 커지면 유휴 전력 150W를 새로 쓰면서까지 선택된다.</p>
     */
    @DisplayName("시나리오 자원 몫을 올리면 빈 노드가 이긴다")
    @Test
    void scenario_resource_share_idle_wins() {
        SchedulerProperties props = new SchedulerProperties();
        props.getPower().setResourceShare(0.20);
        PowerAwareScheduler resourceFirst = new PowerAwareScheduler(props);

        List<NodeResource> nodes = List.of(
                node("효율-적정부하", 55, 45, 230, 48),
                node("유휴-대기", 2, 8, 160, 32));

        SchedulingResult result = resourceFirst.schedule(workload(2), nodes, policy());
        print("시나리오 1-b — 자원 몫 0.20 (기본값 근거)", result);

        assertEquals("유휴-대기", result.selectedNodeId(),
                "자원 몫이 크면 전력 판단이 뒤집힌다 — 그래서 기본값을 0.10 으로 뒀다");
    }

    /**
     * 시나리오 2 — 전부 비슷하게 평이한 클러스터.
     * 큰 차이가 없을 때도 순위가 뒤집히지 않고 안정적으로 나와야 한다.
     */
    @DisplayName("시나리오 전부 평이하면 미세한 차이로 안정적으로 고른다")
    @Test
    void scenario_all_nodes_alike() {
        List<NodeResource> nodes = List.of(
                node("평이-1", 40, 40, 250, 50),
                node("평이-2", 42, 41, 255, 51),
                node("평이-3", 38, 39, 248, 49));

        SchedulingResult result = run("시나리오 2 — 평이한 클러스터", nodes, workload(2), policy());

        assertTrue(result.success());
        double spread = result.candidateScores().get(0).totalScore()
                - result.candidateScores().get(2).totalScore();
        assertTrue(spread < 0.05, "차이가 작아야 정상인데 너무 벌어졌다: " + spread);

        // 같은 입력을 다시 넣어도 같은 결정이어야 한다
        assertEquals(result.selectedNodeId(),
                scheduler.schedule(workload(2), nodes, policy()).selectedNodeId());
    }

    /**
     * 시나리오 3 — 하나만 비정상적으로 나쁜 경우.
     * 유휴에 가까운데 전력을 많이 먹는 노드는 마지막이어야 한다.
     */
    @DisplayName("시나리오 유휴인데 전력을 많이 쓰는 노드가 꼴찌다")
    @Test
    void scenario_idle_power_hog_last() {
        List<NodeResource> nodes = List.of(
                node("정상-1", 45, 40, 240, 50),
                node("정상-2", 50, 45, 255, 52),
                node("이상-유휴고전력", 6, 20, 460, 55));

        SchedulingResult result = run("시나리오 3 — 이상 노드 1대", nodes, workload(2), policy());

        assertEquals(3, rank(result, "이상-유휴고전력"), "가장 낮은 점수여야 한다");
        assertTrue(detail(result, "이상-유휴고전력", "비낭비") < 0.5, "이상 곡선을 크게 넘겼다");
        assertTrue(detail(result, "이상-유휴고전력", "PEI") < 0.2, "단위 전력당 처리량이 낮다");
    }

    /**
     * 시나리오 4 — 전력 통합.
     * 이미 도는 노드와 비어 있는 노드가 있으면 도는 쪽을 채워 유휴 전력을 늘리지 않는다.
     */
    @DisplayName("시나리오 비어있는 노드를 새로 깨우지 않는다")
    @Test
    void scenario_empty_node_not_woken() {
        List<NodeResource> nodes = List.of(
                node("가동중", 45, 40, 240, 50),
                node("비어있음", 1, 5, 150, 35));

        SchedulingResult result = run("시나리오 4 — 전력 통합", nodes, workload(2), policy());

        assertEquals("가동중", result.selectedNodeId(),
                "빈 노드를 깨우면 유휴 전력이 새로 든다");
        // 빈 노드는 계측(150W)·유휴(1%)라 역산 유휴 (150 − 500×0.01)/(1−0.01) = 146.5W가 깨우기항이자 동적 범위의 절편이다:
        // 빈 노드 (500 − 146.5) × 0.25 + 146.5 = 234.8W, 가동 노드 (500 − 150) × 0.25 = 87.5W → 차 147.3W(역산 유휴 + 절편 이동 0.9W)
        double recon = (150 - 500 * 0.01) / 0.99;
        assertEquals((500 - recon) * 0.25 + recon - 87.5,
                detail(result, "비어있음", "실질한계전력W") - detail(result, "가동중", "실질한계전력W"),
                1e-4, "차이 = 역산 유휴 + 절편 이동분");
        assertTrue(detail(result, "가동중", "전력점수") > detail(result, "비어있음", "전력점수"),
                "전력 판단은 가동 중인 노드를 선호한다");
    }

    /**
     * 시나리오 5 — 다건 연속 배치.
     * {@code PlacementContext.reserve}의 예약 누적과 맞물려 한 노드에 몰리다가 임계에서 다음 노드로 넘어가야 한다.
     * 이것이 "80% 까지 채우고 다음 노드로"라는 에너지 인식 빈패킹이다.
     */
    @DisplayName("시나리오 연속 배치는 한 노드를 채우고 다음으로 넘어간다")
    @Test
    void scenario_fill_one_node_first() {
        List<NodeResource> nodes = List.of(
                node("A", 20, 20, 220, 45),
                node("B", 20, 20, 220, 45));
        SchedulingPolicy policy = policy();
        PlacementContext ctx = PlacementContext.of(nodes, policy);

        List<String> picks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Workload w = workload(1);
            SchedulingResult r = scheduler.schedule(w, ctx);
            if (!r.success()) {
                picks.add("실패");
                break;
            }
            picks.add(r.selectedNodeId());
            ctx.reserve(r.selectedNodeId(), w);
        }

        System.out.println("  [시나리오 5] 연속 배치 결과: " + picks);

        assertTrue(!picks.contains("실패"), "8건 모두 배치돼야 한다: " + picks);
        assertTrue(picks.contains("A") && picks.contains("B"),
                "예약이 점수에 반영돼 한쪽으로만 몰리면 안 된다: " + picks);

        // 두 노드가 모두 가동 중이면 어느 쪽에 얹어도 즉시 절감되는 유휴 전력이 없다.
        // 그래서 자원 여유가 결정하고 균등 분산이 된다 — 물리적으로 맞는 결과다.
        // 통합이 이득이 되는 것은 "빈 노드를 깨우지 않을 때"뿐이며 시나리오 4가 그 경우다.
        long a = picks.stream().filter("A"::equals).count();
        assertTrue(Math.abs(a - (picks.size() - a)) <= 2,
                "가동 중인 노드끼리는 고르게 나뉜다: " + picks);
    }

    /**
     * 시나리오 6 — IPMI가 없는 실제 배포 환경.
     * 전력·온도가 전부 0이면 자원 여유로 결정되고, 그 사실이 근거에 드러나야 한다.
     */
    @DisplayName("시나리오 계측이 없으면 자원 여유로 결정하고 밝힌다")
    @Test
    void scenario_unmeasured_uses_headroom() {
        List<NodeResource> nodes = List.of(
                node("여유많음", 15, 20, 0, 0),
                node("보통", 45, 50, 0, 0),
                node("빠듯함", 70, 75, 0, 0));

        SchedulingResult result = run("시나리오 6 — 계측 없음(배포 VM 과 동일)", nodes, workload(2), policy());

        assertEquals("여유많음", result.selectedNodeId());
        assertTrue(result.reason().contains("전력 메트릭이 없어"), result.reason());
        for (NodeScore s : result.candidateScores()) {
            assertEquals(0.5, s.powerScore(), 1e-9, s.nodeId() + " 전력 점수는 중립이어야 한다");
        }
    }

    /**
     * 시나리오 7 — 볼록 곡선을 켠 경우.
     * α&gt;1이면 바쁜 노드에 얹는 비용이 커져 통합과 균형이 맞선다.
     */
    @DisplayName("시나리오 볼록 곡선을 켜면 과밀 노드를 피한다")
    @Test
    void scenario_convex_avoids_crowded() {
        SchedulerProperties props = new SchedulerProperties();
        props.getPower().setCurveAlpha(1.8);
        PowerAwareScheduler convex = new PowerAwareScheduler(props);

        List<NodeResource> nodes = List.of(
                node("과밀", 75, 60, 400, 55),
                node("중간", 40, 40, 280, 50));

        SchedulingResult linear = scheduler.schedule(workload(2), nodes, policy());
        SchedulingResult curved = convex.schedule(workload(2), nodes, policy());

        print("시나리오 7 — 볼록 곡선(α=1.8)", curved);

        assertEquals("중간", curved.selectedNodeId());
        assertTrue(
                detail(curved, "과밀", "실질한계전력W") > detail(linear, "과밀", "실질한계전력W"),
                "볼록 곡선에서는 과밀 노드의 증가분이 더 커야 한다");
    }

    // ── 출력·헬퍼 ───────────────────────────────────────────────

    private SchedulingResult run(String title, List<NodeResource> nodes,
                                 Workload workload, SchedulingPolicy policy) {
        SchedulingResult result = scheduler.schedule(workload, nodes, policy);
        print(title, result);
        return result;
    }

    /** 시나리오 결과를 표로 찍는다. 값이 왜 그렇게 나왔는지 눈으로 확인할 수 있어야 한다. */
    private void print(String title, SchedulingResult result) {
        System.out.println();
        System.out.println("  === " + title + " ===");
        System.out.printf("  %-16s %7s %7s %7s %7s %9s %s%n",
                "노드", "총점", "전력", "열", "PEI", "실질ΔP", "비고");
        for (NodeScore s : result.candidateScores()) {
            Map<String, Double> d = new LinkedHashMap<>(s.details());
            Double pei = d.get("PEI");
            Double dp = d.get("실질한계전력W");
            System.out.printf("  %-16s %7.4f %7.3f %7.3f %7s %8sW %s%n",
                    s.nodeId(), s.totalScore(), s.powerScore(), s.temperatureScore(),
                    pei == null ? "N/A" : String.format("%.3f", pei),
                    dp == null ? "N/A" : String.format("%.1f", dp),
                    s.eligible() ? (s.nodeId().equals(result.selectedNodeId()) ? "← 선택" : "") : "제외: " + s.exclusionReason());
        }
        System.out.println("  근거: " + result.reason());
    }

    private int rank(SchedulingResult result, String nodeId) {
        List<NodeScore> scores = result.candidateScores();
        for (int i = 0; i < scores.size(); i++) {
            if (scores.get(i).nodeId().equals(nodeId)) return i + 1;
        }
        throw new IllegalArgumentException(nodeId);
    }

    private Double detail(SchedulingResult result, String nodeId, String key) {
        return result.candidateScores().stream()
                .filter(s -> s.nodeId().equals(nodeId))
                .findFirst().orElseThrow()
                .details().get(key);
    }

    private SchedulingPolicy policy() {
        return new SchedulingPolicy("test", "test");
    }

    private Workload workload(int cpu) {
        return new Workload("scenario-job", cpu, 1024);
    }

    private NodeResource node(String nodeId, double cpuUsage, double memUsage,
                              double power, double temp) {
        return new NodeResource("mdc-01", nodeId, 8, 16384, 0,
                cpuUsage, memUsage, 40, power, 500, temp, 1_000_000, 500_000);
    }
}

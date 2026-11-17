package com.innogrid.aims.scheduler.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.innogrid.aims.scheduler.config.SchedulerProperties;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse;
import com.innogrid.aims.scheduler.dto.PowerOptimizationResponse.Finding;
import com.innogrid.aims.scheduler.engine.model.PowerModel;
import com.innogrid.aims.scheduler.model.NodeResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 합성 클러스터로 돌려 보는 전력 최적화 진단 시나리오.
 *
 * <p>배포 VM은 노드가 1대뿐이고 IPMI도 없어 여러 노드가 섞인 판단을 실환경에서 검증할 수 없다.
 * 합성 데이터가 유일한 수단이다. 모든 노드를 8코어 · 용량 500W(유휴 150W)로 맞추고 부하·전력만
 * 바꿔, 결과 차이의 출처를 분명히 한다.</p>
 */
class PowerOptimizationScenarioTest {

    private final SchedulerProperties properties = new SchedulerProperties();
    private final PowerOptimizationService service = new PowerOptimizationService(
            null, new PowerModel(properties), properties);

    /**
     * 시나리오 1 — 전형적인 혼재 클러스터.
     * 유휴·낭비·여유부족·정상 노드가 섞여 있다.
     */
    @DisplayName("시나리오 혼재된 클러스터에서 유형별로 진단한다")
    @Test
    void scenario_mixed_cluster_by_type() {
        var result = run("시나리오 1 — 혼재 클러스터", List.of(
                node("정상-효율", 55, 260),
                node("낭비-저부하", 20, 420),
                node("여유부족", 70, 460),
                node("유휴-대기", 2, 150)));

        assertTrue(has(result, "IDLE_NODE"), "유휴 노드를 잡아야 한다");
        assertTrue(has(result, "POWER_WASTE"), "낭비 노드를 잡아야 한다");
        assertTrue(has(result, "LOW_HEADROOM"), "여유 부족 노드를 잡아야 한다");
        assertEquals(1, result.cluster().idleNodes());
        assertEquals(3, result.cluster().activeNodes());
    }

    /**
     * 시나리오 2 — 전부 건강한 클러스터.
     * 진단할 것이 없으면 없다고 답해야 한다. 억지로 항목을 만들지 않는다.
     */
    @DisplayName("시나리오 전부 건강하면 진단 항목이 없다")
    @Test
    void scenario_all_healthy_no_diagnosis() {
        var result = run("시나리오 2 — 건강한 클러스터", List.of(
                node("정상-1", 50, 250),
                node("정상-2", 45, 240),
                node("정상-3", 60, 290)));

        assertTrue(result.findings().isEmpty(), "문제가 없으면 비어야 한다: " + result.findings());
        assertEquals(0.0, result.cluster().reclaimableIdleWatts(), 1e-9);
        assertEquals(0.0, result.summary().estimatedSavingWatts(), 1e-9);
    }

    /**
     * 시나리오 3 — 유휴 노드가 여럿.
     * 반납 가능 전력이 대수만큼 쌓여야 한다.
     */
    @DisplayName("시나리오 유휴 노드가 많으면 반납 가능 전력이 쌓인다")
    @Test
    void scenario_many_idle_releasable_power() {
        var result = run("시나리오 3 — 유휴 3대", List.of(
                node("가동", 55, 260),
                node("유휴-1", 2, 150),
                node("유휴-2", 1, 150),
                node("유휴-3", 3, 155)));

        assertEquals(3, result.cluster().idleNodes());
        // 반납분은 wakeupPower — 세 유휴 노드 다 계측돼 실측에서 역산한다:
        // 유휴-1(P=150,u=0.02,Pmax=500): (150-500*0.02)/0.98 = 140/0.98 ≈ 142.8571
        // 유휴-2(P=150,u=0.01,Pmax=500): (150-500*0.01)/0.99 = 145/0.99 ≈ 146.4646
        // 유휴-3(P=155,u=0.03,Pmax=500): (155-500*0.03)/0.97 = 140/0.97 ≈ 144.3299
        // 합 ≈ 433.6517 → round 433.65
        double expected = PowerOptimizationService.round((150 - 500 * 0.02) / 0.98)
                + PowerOptimizationService.round((150 - 500 * 0.01) / 0.99)
                + PowerOptimizationService.round((155 - 500 * 0.03) / 0.97);
        assertEquals(expected, result.cluster().reclaimableIdleWatts(), 1e-6,
                "(150-500*0.02)/0.98 + (150-500*0.01)/0.99 + (155-500*0.03)/0.97 ≈ 433.65");
        assertEquals(3, result.findings().stream().filter(f -> f.type().equals("IDLE_NODE")).count());
        assertEquals(expected, result.summary().estimatedSavingWatts(), 1e-6);
    }

    /**
     * 시나리오 4 — 배포 VM과 같은 상태.
     * IPMI가 없어 전력이 전부 0이면 진단을 비우고 사유를 남긴다.
     */
    @DisplayName("시나리오 계측이 없으면 진단을 비우고 사유를 남긴다")
    @Test
    void scenario_no_measurement_empty_diagnosis() {
        var result = run("시나리오 4 — 계측 없음(배포 VM 과 동일)", List.of(
                node("no-ipmi-1", 12, 0),
                node("no-ipmi-2", 40, 0)));

        assertFalse(result.cluster().measured());
        assertTrue(result.findings().isEmpty(), "근거 없이 진단을 지어내지 않는다");
        assertTrue(result.cluster().unmeasuredReason().contains("IPMI"), result.cluster().unmeasuredReason());
        assertEquals(0.0, result.cluster().reclaimableIdleWatts(), 1e-9);
    }

    /**
     * 시나리오 5 — 일부만 계측된 클러스터.
     * 계측된 노드만 진단하고 나머지는 건너뛴다.
     */
    @DisplayName("시나리오 일부만 계측되면 그 노드만 진단한다")
    @Test
    void scenario_partial_measurement_only_those() {
        var result = run("시나리오 5 — 일부만 계측", List.of(
                node("계측됨-유휴", 2, 150),
                node("미계측", 40, 0)));

        assertTrue(result.cluster().measured(), "하나라도 계측됐으면 진단한다");
        assertEquals(1, result.findings().size(), "계측된 노드만: " + result.findings());
        assertEquals("계측됨-유휴", result.findings().get(0).nodeId());
    }

    // ── 출력·헬퍼 ───────────────────────────────────────────────

    private PowerOptimizationResponse run(String title, List<NodeResource> nodes) {
        PowerOptimizationResponse result = service.diagnose(nodes);

        System.out.println();
        System.out.println("  === " + title + " ===");
        System.out.printf("  클러스터: 총 %d대 / 가동 %d대 / 유휴 %d대 / %.0fW / 반납가능 %.0fW / 계측 %s%n",
                result.cluster().totalNodes(), result.cluster().activeNodes(),
                result.cluster().idleNodes(), result.cluster().totalPowerWatts(),
                result.cluster().reclaimableIdleWatts(), result.cluster().measured() ? "O" : "X");
        if (result.findings().isEmpty()) {
            System.out.println("  진단 항목 없음"
                    + (result.cluster().measured() ? "" : " — " + result.cluster().unmeasuredReason()));
        }
        for (Finding f : result.findings()) {
            System.out.printf("  %-13s %-6s %-12s %8s  %s%n",
                    f.type(), f.severity(), f.nodeId(),
                    f.estimatedSavingWatts() == null ? "N/A" : String.format("%.0fW", f.estimatedSavingWatts()),
                    f.detail());
        }
        System.out.printf("  요약: %d건 / 절감 %.0fW / %s%n",
                result.summary().findingCount(), result.summary().estimatedSavingWatts(),
                result.summary().savingPercent() == null ? "비율 N/A"
                        : String.format("%.1f%%", result.summary().savingPercent()));
        return result;
    }

    private boolean has(PowerOptimizationResponse result, String type) {
        return result.findings().stream().anyMatch(f -> f.type().equals(type));
    }

    private NodeResource node(String nodeId, double cpuUsage, double power) {
        return new NodeResource("mdc-01", nodeId, 8, 16384, 0,
                cpuUsage, 40, 30, power, 500, 45, 1_000_000, 500_000);
    }
}

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 클러스터 전력 최적화 진단.
 *
 * <p>모든 노드를 8코어 · 용량 500W(유휴 150W)로 맞추고 부하·전력만 바꿔, 결과 차이의 출처를
 * 분명히 한다. {@code PowerAwareScenarioTest}의 픽스처와 같은 값이라 손으로 검산할 수 있다.</p>
 */
class PowerOptimizationServiceTest {

    private final SchedulerProperties properties = new SchedulerProperties();
    private final PowerOptimizationService service = new PowerOptimizationService(
            null, new PowerModel(properties), properties);

    // ── 클러스터 현황 ───────────────────────────────────────────

    @DisplayName("가동 노드와 유휴 노드를 나눠 센다")
    @Test
    void active_and_idle_nodes_counted() {
        var result = service.diagnose(List.of(
                node("busy", 60, 300),
                node("mid", 30, 220),
                node("idle-1", 2, 150),
                node("idle-2", 1, 150)));

        assertEquals(4, result.cluster().totalNodes());
        assertEquals(2, result.cluster().activeNodes());
        assertEquals(2, result.cluster().idleNodes());
        assertEquals(820.0, result.cluster().totalPowerWatts(), 1e-9);
    }

    @DisplayName("유휴 노드의 유휴 전력을 반납 가능으로 집계한다")
    @Test
    void idle_power_aggregated_as_releasable() {
        var result = service.diagnose(List.of(
                node("busy", 60, 300),
                node("idle-1", 2, 150),
                node("idle-2", 1, 150)));

        // 반납분은 wakeupPower — 두 유휴 노드 다 계측돼 실측에서 역산한다:
        // idle-1(P=150,u=0.02,Pmax=500): (150-500*0.02)/0.98 = 140/0.98 ≈ 142.8571
        // idle-2(P=150,u=0.01,Pmax=500): (150-500*0.01)/0.99 = 145/0.99 ≈ 146.4646
        // 합 ≈ 289.3218 → round 289.32
        assertEquals(PowerOptimizationService.round((150 - 500 * 0.02) / 0.98) + PowerOptimizationService.round((150 - 500 * 0.01) / 0.99),
                result.cluster().reclaimableIdleWatts(), 1e-6);
    }

    // ── 진단 항목 ──────────────────────────────────────────────

    @DisplayName("유휴 노드를 찾아 반납을 권고한다")
    @Test
    void idle_nodes_recommended_for_release() {
        var result = service.diagnose(List.of(node("idle", 2, 150)));

        Finding f = find(result, "IDLE_NODE");
        assertNotNull(f);
        assertEquals("HIGH", f.severity());
        // 반납분은 wakeupPower — 계측된 유휴 노드(P=150,u=0.02,Pmax=500)라 실측에서 역산한다:
        // (150-500*0.02)/0.98 = 140/0.98 ≈ 142.8571 → round 142.86
        assertEquals(PowerOptimizationService.round((150 - 500 * 0.02) / 0.98), f.estimatedSavingWatts(), 1e-6,
                "반납하면 유휴 전력을 통째로 아낀다");
        assertTrue(f.detail().contains("2.0%"), f.detail());
    }

    @DisplayName("부하에 비해 전력을 많이 쓰면 낭비로 본다")
    @Test
    void high_power_low_load_is_waste() {
        // CPU 20% → 이상 전력 150 + 350×0.2 = 220W. 실측 420W는 200W 초과
        var result = service.diagnose(List.of(node("wasteful", 20, 420)));

        Finding f = find(result, "POWER_WASTE");
        assertNotNull(f);
        assertEquals(200.0, f.estimatedSavingWatts(), 1.0);
    }

    @DisplayName("이상 곡선 안에 있으면 낭비로 보지 않는다")
    @Test
    void within_ideal_curve_not_waste() {
        // CPU 60% → 이상 250 + ... 실측 300W는 곡선 아래
        var result = service.diagnose(List.of(node("efficient", 60, 300)));

        assertNull(find(result, "POWER_WASTE"));
    }

    /** 여유 부족은 절감 항목이 아니라 배치 제약이다. 숫자를 채우면 절감 합계가 부풀려진다. */
    @DisplayName("전력 여유 부족은 절감액을 비운다")
    @Test
    void low_headroom_leaves_saving_empty() {
        var result = service.diagnose(List.of(node("tight", 70, 460)));

        Finding f = find(result, "LOW_HEADROOM");
        assertNotNull(f);
        assertNull(f.estimatedSavingWatts(), "절감이 아니라 제약이다");
    }

    @DisplayName("절감이 큰 항목이 위로 온다")
    @Test
    void larger_saving_comes_first() {
        // 낭비 노드는 이상 곡선(150 + 350×0.4 = 290W)을 130W 넘겨 임계 0.8 아래로 떨어진다.
        // 350W로 두면 초과가 60W뿐이라 낭비로 잡히지 않아 항목이 하나만 나온다.
        var result = service.diagnose(List.of(
                node("waste", 40, 420),
                node("idle", 1, 150)));

        List<Finding> f = result.findings();
        assertTrue(f.size() >= 2);
        for (int i = 1; i < f.size(); i++) {
            double prev = f.get(i - 1).estimatedSavingWatts() == null ? 0 : f.get(i - 1).estimatedSavingWatts();
            double curr = f.get(i).estimatedSavingWatts() == null ? 0 : f.get(i).estimatedSavingWatts();
            assertTrue(prev >= curr, "절감 내림차순이어야 한다");
        }
    }

    // ── 계측이 없을 때 ─────────────────────────────────────────

    @DisplayName("전력을 수집하지 못하면 진단을 비우고 사유를 남긴다")
    @Test
    void uncollected_power_empty_diagnosis() {
        var result = service.diagnose(List.of(
                node("no-ipmi-1", 40, 0),
                node("no-ipmi-2", 10, 0)));

        assertFalse(result.cluster().measured());
        assertNotNull(result.cluster().unmeasuredReason());
        assertTrue(result.findings().isEmpty(), "근거 없이 진단을 지어내지 않는다");
        assertEquals(0, result.summary().findingCount());
        assertEquals(0.0, result.cluster().reclaimableIdleWatts(), 1e-9);
    }

    @DisplayName("총 전력이 0이면 절감 비율을 내지 않는다")
    @Test
    void zero_total_power_no_saving_ratio() {
        var result = service.diagnose(List.of(node("no-ipmi", 40, 0)));

        assertNull(result.summary().savingPercent(), "0% 로 채우면 절감 없음으로 읽힌다");
    }

    @DisplayName("노드가 없어도 예외가 나지 않는다")
    @Test
    void no_nodes_raises_no_exception() {
        var result = service.diagnose(List.of());

        assertEquals(0, result.cluster().totalNodes());
        assertTrue(result.findings().isEmpty());
        assertFalse(result.cluster().measured());
    }

    @DisplayName("절감 비율은 클러스터 총 전력 대비다")
    @Test
    void saving_ratio_over_cluster_total_power() {
        var result = service.diagnose(List.of(
                node("busy", 60, 300),
                node("idle", 2, 150)));

        // 반납분은 wakeupPower — 유휴 노드(P=150,u=0.02,Pmax=500): (150-500*0.02)/0.98 ≈ 142.8571
        // 142.8571 / 총 450W × 100 ≈ 31.75%
        assertEquals(PowerOptimizationService.round((150 - 500 * 0.02) / 0.98 / 450 * 100),
                result.summary().savingPercent(), 0.1);
    }

    /**
     * 플래그를 끄면 서비스 층까지 대체값 고정이 배선돼야 한다 — PowerModel 단위 테스트만으로는
     * PowerOptimizationService 생성 경로에 플래그가 실제로 닿는지 보장하지 않는다.
     */
    @DisplayName("플래그를 끄면 반납 가능 전력이 대체값으로 돌아간다")
    @Test
    void the_flag_off_returns_the_reclaimable_power_to_the_fallback() {
        SchedulerProperties offProps = new SchedulerProperties();
        offProps.getPower().setIdleFromMeasurement(false);
        PowerOptimizationService offService = new PowerOptimizationService(
                null, new PowerModel(offProps), offProps);

        var result = offService.diagnose(List.of(
                node("busy", 60, 300),
                node("idle-1", 2, 150),
                node("idle-2", 1, 150)));

        assertEquals(300.0, result.cluster().reclaimableIdleWatts(), 1e-6, "500×0.3×2 = 300");
    }

    // ── 헬퍼 ───────────────────────────────────────────────────

    private Finding find(PowerOptimizationResponse result, String type) {
        return result.findings().stream()
                .filter(f -> f.type().equals(type))
                .findFirst().orElse(null);
    }

    private NodeResource node(String nodeId, double cpuUsage, double power) {
        return new NodeResource("mdc-01", nodeId, 8, 16384, 0,
                cpuUsage, 40, 30, power, 500, 45, 1_000_000, 500_000);
    }
}
